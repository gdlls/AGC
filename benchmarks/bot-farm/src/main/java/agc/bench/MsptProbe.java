package agc.bench;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls the Paper {@code mspt} command over RCON at a fixed interval, extracts p50/p95/p99
 * samples, and renders a final aggregate report (roadmap Phase 1-3).
 *
 * <p>The parser is intentionally lenient: Paper's mspt output format has drifted across versions.
 * We scan for {@code p50}/{@code p95}/{@code p99} tokens followed by numbers anywhere in the text.</p>
 */
public final class MsptProbe implements AutoCloseable {

    private static final Pattern METRIC_PATTERN =
        Pattern.compile("(p50|p95|p99|avg)\\s*[:=]?\\s*([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    private final String host;
    private final int port;
    private final String password;

    private final Map<String, List<Double>> samples = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "agc-bench-mspt");
            t.setDaemon(true);
            return t;
        });

    private volatile RconClient rcon;

    public MsptProbe(final String host, final int port, final String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    public void start(final long intervalMillis) throws IOException {
        this.rcon = new RconClient();
        this.rcon.connect(this.host, this.port, this.password);
        this.scheduler.scheduleAtFixedRate(this::pollOnce, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** One-shot console command execution for scenario setup. */
    public void execSetupCommand(final String cmd) throws IOException {
        if (this.rcon == null) {
            final RconClient oneShot = new RconClient();
            try {
                oneShot.connect(this.host, this.port, this.password);
                oneShot.command(cmd);
            } finally {
                oneShot.close();
            }
            return;
        }
        this.rcon.command(cmd);
    }

    private void pollOnce() {
        try {
            final String out = this.rcon.command("mspt");
            final Matcher m = METRIC_PATTERN.matcher(out);
            while (m.find()) {
                this.samples.computeIfAbsent(m.group(1).toLowerCase(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(Double.parseDouble(m.group(2)));
            }
        } catch (final Throwable t) {
            System.err.println("[bench] mspt poll failed: " + t.getMessage());
        }
    }

    /** Prints the aggregated report and returns true when all SLO gates pass. */
    public boolean printReport() {
        System.out.println("=====================================================");
        System.out.println("  AGC BENCH MSPT REPORT");
        System.out.println("=====================================================");
        boolean allPass = !this.samples.isEmpty();
        for (final Map.Entry<String, List<Double>> e : this.samples.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            final List<Double> sorted = new ArrayList<>(e.getValue());
            Collections.sort(sorted);
            final double min = sorted.get(0);
            final double max = sorted.get(sorted.size() - 1);
            final double median = percentile(sorted, 0.5);
            final double p95 = percentile(sorted, 0.95);
            System.out.printf(java.util.Locale.ROOT, "  %-4s n=%-5d min=%6.2f median=%6.2f p95=%6.2f max=%6.2f%n",
                e.getKey(), sorted.size(), min, median, p95, max);
        }
        if (!allPass) {
            System.out.println("  (no mspt samples collected — check RCON credentials/output format)");
        } else {
            final List<Double> p99s = this.samples.getOrDefault("p99", List.of());
            if (!p99s.isEmpty()) {
                final List<Double> sorted = new ArrayList<>(p99s);
                Collections.sort(sorted);
                final double worst = sorted.get((int) Math.floor(0.95 * (sorted.size() - 1)));
                allPass = worst <= 45.0; // roadmap SLO: MSPT p99 <= 45ms
                System.out.printf(java.util.Locale.ROOT, "  SLO gate p99<=45ms: %s (%.2f)%n",
                    allPass ? "PASS" : "FAIL", worst);
            }
        }
        System.out.println("=====================================================");
        return allPass;
    }

    private static double percentile(final List<Double> sorted, final double q) {
        final int idx = (int) Math.round(q * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    @Override
    public void close() {
        this.scheduler.shutdownNow();
        if (this.rcon != null) {
            try {
                this.rcon.close();
            } catch (final IOException ignored) {}
        }
    }
}
