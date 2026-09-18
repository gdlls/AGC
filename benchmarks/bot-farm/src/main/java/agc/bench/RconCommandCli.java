package agc.bench;

import java.util.regex.Pattern;

/**
 * Runs arbitrary console commands against a live server over RCON. Complements {@link HarnessMain},
 * which only issues the per-scenario setup commands baked into {@link Scenarios}, by letting an
 * operator inspect a running benchmark (MSPT breakdown, itemised AGC status, mode switches).
 *
 * <pre>
 * gradlew -p benchmarks/bot-farm rcon --args="127.0.0.1 25575 bench \"agc status\""
 * </pre>
 *
 * <p>The connection is opened once and reused for every command, because the vanilla RCON listener
 * serves a single client at a time and resets a reconnect that races the previous socket's teardown.</p>
 */
public final class RconCommandCli {

    private static final Pattern COLOR = Pattern.compile("\u00a7[0-9a-fk-orA-FK-OR]");

    public static void main(final String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: rcon <host> <port> <password> <command> [command...]");
            System.exit(1);
        }

        final String host = args[0];
        final int port = Integer.parseInt(args[1]);
        final String password = args[2];

        try (RconClient rcon = new RconClient()) {
            rcon.connect(host, port, password);
            for (int i = 3; i < args.length; i++) {
                System.out.println("$ " + args[i]);
                System.out.println(clean(rcon.command(args[i])));
            }
        }
    }

    /** Drops section-sign color codes and unmappable glyphs so console output is readable. */
    private static String clean(final String raw) {
        final String stripped = COLOR.matcher(raw).replaceAll("");
        final StringBuilder sb = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            final char c = stripped.charAt(i);
            sb.append(c == '\ufffd' ? '?' : c);
        }
        return sb.toString().trim();
    }
}
