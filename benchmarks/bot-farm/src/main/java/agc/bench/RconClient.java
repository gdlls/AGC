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

        writePacket(TYPE_AUTH, password);
        readPacket(); // auth response payload
        final int authResult = readType();
        if (authResult == -1) {
            throw new IOException("rcon auth failed");
        }
    }

    /** Executes a console command and returns the server's text response. */
    public String command(final String cmd) throws IOException {
        writePacket(TYPE_COMMAND, cmd);
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        while (true) {
            final int len = this.in.readInt();
            final int id = this.in.readInt();
            final int type = this.in.readInt();
            final byte[] body = new byte[Math.max(0, len - 10)];
            this.in.readFully(body);
            this.in.readFully(new byte[2]);
            payload.write(body);
            if (type == 0 && id != -1) {
                break; // single-segment responses are typical for mspt-sized outputs
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
        final int id = ++this.requestId;
        this.out.writeInt(4 + 4 + bodyBytes.length + 2);
        this.out.writeInt(id);
        this.out.writeInt(type);
        this.out.write(bodyBytes);
        this.out.writeByte(0);
        this.out.writeByte(0);
        this.out.flush();
    }

    private void readPacket() throws IOException {
        final int len = this.in.readInt();
        this.in.readInt(); // id
        final byte[] body = new byte[Math.max(0, len - 10)];
        this.in.readFully(body);
        this.in.readFully(new byte[2]);
    }

    private int readType() throws IOException {
        final int len = this.in.readInt();
        final int id = this.in.readInt();
        final int type = this.in.readInt();
        this.in.readFully(new byte[Math.max(0, len - 10)]);
        this.in.readFully(new byte[2]);
        return id == -1 ? -1 : type;
    }
}
