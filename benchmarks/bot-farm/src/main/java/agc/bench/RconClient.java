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

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int requestId = 1;

    public void connect(final String host, final int port, final String password) throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setSoTimeout(5_000);
        this.in = new DataInputStream(this.socket.getInputStream());
        this.out = new DataOutputStream(this.socket.getOutputStream());

        final int currentReq = this.requestId;
        writePacket(TYPE_AUTH, password);
        final RconPacket resp = readPacket();
        if (resp.id == -1 || resp.id != currentReq) {
            throw new IOException("rcon auth failed: id mismatch or rejected (id=" + resp.id + ", expected=" + currentReq + ")");
        }
    }

    /** Executes a console command and returns the server's text response. */
    public String command(final String cmd) throws IOException {
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
        this.socket.getOutputStream().write(baos.toByteArray());
        this.socket.getOutputStream().flush();
    }

    private void writeIntLE(final int value) throws IOException {
        this.out.writeInt(Integer.reverseBytes(value));
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
