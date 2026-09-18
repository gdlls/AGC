package agc.bench;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Source RCON client (no external dependencies). Used to execute console commands and
 * poll the {@code mspt} output during benchmarks.
 */
public final class RconClient implements AutoCloseable {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;

    /**
     * Attempts per {@link #connect} call. Vanilla's RCON thread tears the session down as soon as a
     * single {@code read} does not line up with the frame length it decoded, and it also serves one
     * client at a time, so a reconnect issued right after a previous socket closed can be reset.
     * Retrying with backoff keeps benchmark runs from dying on a transient RCON hiccup.
     */
    private static final int CONNECT_ATTEMPTS = 5;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int requestId = 1;

    public void connect(final String host, final int port, final String password) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            try {
                this.socket = new Socket(host, port);
                this.socket.setSoTimeout(15_000);
                this.socket.setTcpNoDelay(true);
                this.in = new DataInputStream(this.socket.getInputStream());
                this.out = new DataOutputStream(this.socket.getOutputStream());

                final int currentReq = this.requestId;
                writePacket(TYPE_AUTH, password);
                final RconPacket resp = readPacket();
                if (resp.id == -1 || resp.id != currentReq) {
                    throw new IOException("rcon auth failed: id mismatch or rejected (id=" + resp.id + ", expected=" + currentReq + ")");
                }
                return;
            } catch (final IOException e) {
                last = e;
                closeQuietly();
                if (attempt < CONNECT_ATTEMPTS) {
                    try {
                        Thread.sleep(250L * attempt);
                    } catch (final InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while retrying rcon connect", ie);
                    }
                }
            }
        }
        throw new IOException("rcon connect to " + host + ':' + port + " failed after "
            + CONNECT_ATTEMPTS + " attempts", last);
    }

    private void closeQuietly() {
        if (this.socket != null) {
            try {
                this.socket.close();
            } catch (final IOException ignored) {}
            this.socket = null;
        }
    }

    /** Executes a console command and returns the server's text response. */
    public synchronized String command(final String cmd) throws IOException {
        final int currentReq = this.requestId;
        writePacket(TYPE_COMMAND, cmd);
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        while (true) {
            final RconPacket pkt = readPacket();
            payload.write(pkt.body);
            if (pkt.type == 0 && pkt.id == currentReq) {
                break; // single-segment or final segment
            }
        }
        return payload.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        if (this.socket != null && !this.socket.isClosed()) {
            this.socket.close();
        }
    }

    /**
     * Serializes the whole frame in memory and hands it to the socket in ONE write. Writing the four
     * header ints directly to the socket lets them land in separate TCP segments, and the server's
     * single-read framing check then discards the connection.
     */
    private void writePacket(final int type, final String body) throws IOException {
        final byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        final int id = this.requestId++;
        final int payloadLen = 4 + 4 + bodyBytes.length + 2;
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(4 + payloadLen);
        final DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(Integer.reverseBytes(payloadLen));
        dos.writeInt(Integer.reverseBytes(id));
        dos.writeInt(Integer.reverseBytes(type));
        dos.write(bodyBytes);
        dos.writeByte(0);
        dos.writeByte(0);
        dos.flush();
        this.out.write(baos.toByteArray());
        this.out.flush();
    }

    private int readIntLE() throws IOException {
        return Integer.reverseBytes(this.in.readInt());
    }

    private RconPacket readPacket() throws IOException {
        final int len = readIntLE();
        if (len < 10 || len > 1460 * 10) {
            throw new IOException("Invalid RCON packet length: " + len);
        }
        final int id = readIntLE();
        final int type = readIntLE();
        final int bodyLen = len - 10;
        final byte[] body = new byte[bodyLen];
        if (bodyLen > 0) {
            this.in.readFully(body);
        }
        final byte[] pad = new byte[2];
        this.in.readFully(pad);
        return new RconPacket(id, type, body);
    }

    private record RconPacket(int id, int type, byte[] body) {}
}
