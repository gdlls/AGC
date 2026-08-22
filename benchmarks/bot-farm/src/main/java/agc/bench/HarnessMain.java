package agc.bench;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLI entrypoint.
 *
 * <pre>
 * gradlew -p benchmarks/bot-farm run --args="
 *   --host 127.0.0.1 --port 25565
 *   --bots 500 --scenario dense-combat
 *   --duration-s 300 --join-rate 25
 *   --rcon-port 25575 --rcon-pass bench"
 * </pre>
 */
public final class HarnessMain {

    public static void main(final String[] args) throws Exception {
        final Map<String, String> opts = parseArgs(args);

        final String host = opts.getOrDefault("host", "127.0.0.1");
        final int port = Integer.parseInt(opts.getOrDefault("port", "25565"));
        final int bots = Integer.parseInt(opts.getOrDefault("bots", "100"));
        final String scenarioName = opts.getOrDefault("scenario", "dense-combat");
        final int durationSeconds = Integer.parseInt(opts.getOrDefault("duration-s", "0"));
        final double joinRate = Double.parseDouble(opts.getOrDefault("join-rate", "25"));
        final String rconPass = opts.getOrDefault("rcon-pass", "");

        final Scenario scenario = Scenarios.byName(scenarioName);
        System.out.printf("[bench] scenario=%s bots=%d join-rate=%.1f/s%n", scenario.name(), bots, joinRate);

        final MsptProbe probe = new MsptProbe(host, Integer.parseInt(opts.getOrDefault("rcon-port", "25575")), rconPass);
        try {
            if (!rconPass.isEmpty()) {
                probe.start(2_000);
                for (final String cmd : scenario.serverSetupCommands()) {
                    System.out.println("[bench] setup> " + cmd);
                    probe.execSetupCommand(cmd);
                }
            } else {
                System.out.println("[bench] no --rcon-pass given; skipping MSPT sampling + setup commands");
            }

            final AtomicInteger botSeq = new AtomicInteger();
            try (BotFarm farm = new BotFarm(() -> new BotClient(host, port, "benchbot-" + botSeq.incrementAndGet()))) {
                farm.spawnStaged(bots, joinRate);
                System.out.println("[bench] joined=" + farm.totalJoined());

                if (scenario.isLoginChurn()) {
                    farm.startChurnLoop(scenario.churnPerSecond());
                }
                farm.startScenarioLoop(scenario);

                final int seconds = durationSeconds > 0 ? durationSeconds : scenario.durationTicks() / 20;
                Thread.sleep(seconds * 1000L);

                System.out.println("[bench] connected at end=" + farm.connectedCount()
                    + " totalJoined=" + farm.totalJoined());
            }

            final boolean pass = rconPass.isEmpty() || probe.printReport();
            if (!pass) {
                System.exit(2); // SLO gate failed — non-zero for CI
            }
        } finally {
            probe.close();
        }
    }

    private static Map<String, String> parseArgs(final String[] args) {
        final Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            final String key = args[i];
            if (key.startsWith("--")) {
                out.put(key.substring(2), args[i + 1]);
            }
        }
        return out;
    }
}
