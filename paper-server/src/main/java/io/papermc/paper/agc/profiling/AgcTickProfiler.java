package io.papermc.paper.agc.profiling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * AGC — High-Resolution Real-Time Tick Subsystem Profiler.
 *
 * <p>Instruments nanosecond-accurate execution times across all core server subsystems
 * (World Ticking, Entities, Block Entities, Chunk IO/Gen, Network, Plugin Events, Redstone/Block Updates).
 * Retains a lock-free 1200-tick (60-second) rolling ring buffer, calculates statistical percentiles
 * (p50, p95, p99, max), and exposes telemetry via JMX and the {@code /agc profile} command.</p>
 */
public final class AgcTickProfiler implements AgcTickProfilerMXBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcTickProfiler.class);
    private static final AgcTickProfiler INSTANCE = new AgcTickProfiler();

    public static final int BUFFER_SIZE = 1200; // 60 seconds at 20 TPS

    public enum Subsystem {
        WORLD_TICK("World Ticking", "World execution, climate, raids & timers"),
        ENTITY_TICK("Entities", "Living & non-living entity movement & AI"),
        BLOCK_ENTITY_TICK("Block Entities", "Tile entities (hoppers, furnaces, chests)"),
        CHUNK_LOAD("Chunk Loading/IO", "Disk read/deserialization & ticket level management"),
        CHUNK_GEN("Chunk Generation", "Biome noise, features, carving & structures"),
        NETWORK("Network & Packets", "Packet decoding, handling, dispatch & Netty I/O"),
        PLUGIN_EVENT("Plugin Events", "Bukkit / Paper synchronous event listeners"),
        SCHEDULED_TICK("Scheduled Ticks", "Block and fluid scheduled tick execution"),
        CROSS_WORLD("Cross-World Ops", "Portals, inter-world entity moves & cross-queues"),
        BLOCK_UPDATE("Block Physics/Updates", "Redstone, neighbor updates & light updates"),
        AUTOSAVE("Autosave & Disk Commit", "Incremental chunk & player saves"),
        OTHER("Internal Overhead", "Watchdog, scheduler heartbeats & misc tasks");

        private final String displayName;
        private final String description;

        Subsystem(final String displayName, final String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String displayName() {
            return this.displayName;
        }

        public String description() {
            return this.description;
        }
    }

    public record TickSnapshot(
        long tickNumber,
        long totalDurationNanos,
        long[] subsystemNanos
    ) {
        public double totalMs() {
            return this.totalDurationNanos / 1_000_000.0;
        }

        public double subsystemMs(final Subsystem sub) {
            return (sub.ordinal() < this.subsystemNanos.length ? this.subsystemNanos[sub.ordinal()] : 0L) / 1_000_000.0;
        }
    }

    private final AtomicReferenceArray<TickSnapshot> ringBuffer = new AtomicReferenceArray<>(BUFFER_SIZE);
    private final AtomicLong currentTickCounter = new AtomicLong();

    private static final int SUBSYSTEM_COUNT = Subsystem.values().length;
    private final ThreadLocal<long[]> activeSubsystemNanos = ThreadLocal.withInitial(() -> new long[SUBSYSTEM_COUNT]);
    private final ThreadLocal<Long> activeTickStartNanos = new ThreadLocal<>();
    private final ThreadLocal<Long> activeTickNumber = new ThreadLocal<>();

    public static AgcTickProfiler get() {
        return INSTANCE;
    }

    private AgcTickProfiler() {
        registerMBean();
    }

    private void registerMBean() {
        try {
            final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            final ObjectName name = new ObjectName("io.papermc.paper.agc:type=TickProfiler");
            if (!mbs.isRegistered(name)) {
                mbs.registerMBean(this, name);
                LOGGER.info("[AGC] Tick Profiler JMX MXBean registered successfully.");
            }
        } catch (final Throwable t) {
            LOGGER.warn("[AGC] Could not register Tick Profiler JMX MBean: {}", t.getMessage());
        }
    }


    /**
     * Starts instrumentation for the given tick on the current thread.
     */
    public void startTick(final long tickNumber) {
        this.activeTickNumber.set(tickNumber);
        this.activeTickStartNanos.set(System.nanoTime());
        final long[] nanos = this.activeSubsystemNanos.get();
        Arrays.fill(nanos, 0L);
    }

    /**
     * Records elapsed nanoseconds for a specific subsystem in the current active tick.
     */
    public void recordSubsystem(final Subsystem subsystem, final long durationNanos) {
        if (subsystem == null || durationNanos <= 0) return;
        final long[] nanos = this.activeSubsystemNanos.get();
        final int idx = subsystem.ordinal();
        if (idx < nanos.length) {
            nanos[idx] += durationNanos;
        }
    }

    /**
     * Creates an AutoCloseable sample scope that automatically measures and records subsystem duration.
     */
    public Sample startSample(final Subsystem subsystem) {
        if (subsystem == null) return NoopSample.INSTANCE;
        final long start = System.nanoTime();
        return () -> recordSubsystem(subsystem, System.nanoTime() - start);
    }

    /**
     * Completes instrumentation for the current tick and commits the snapshot to the ring buffer.
     */
    public void endTick(final long tickNumber, final long totalDurationNanos) {
        final long[] recorded = this.activeSubsystemNanos.get();
        final long[] copy = Arrays.copyOf(recorded, SUBSYSTEM_COUNT);

        long recordedSum = 0;
        for (final long n : copy) recordedSum += n;
        final long overhead = Math.max(0, totalDurationNanos - recordedSum);
        copy[Subsystem.OTHER.ordinal()] += overhead;

        final TickSnapshot snapshot = new TickSnapshot(tickNumber, totalDurationNanos, copy);
        final int slot = (int) (tickNumber % BUFFER_SIZE);
        this.ringBuffer.set(slot, snapshot);
        this.currentTickCounter.set(tickNumber);
    }

    @FunctionalInterface
    public interface Sample extends AutoCloseable {
        @Override
        void close();
    }

    private static final class NoopSample implements Sample {
        static final NoopSample INSTANCE = new NoopSample();
        @Override public void close() {}
    }


    public record SubsystemStats(
        Subsystem subsystem,
        double averageMs,
        double percentageOfTotal,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double maxMs
    ) {}

    public record ProfilerSummary(
        int sampleCount,
        double averageMspt,
        double p50Mspt,
        double p95Mspt,
        double p99Mspt,
        double maxMspt,
        Map<Subsystem, SubsystemStats> subsystemStats
    ) {}

    /**
     * Analyzes recent ticks within the requested window size (up to 1200 ticks).
     */
    public ProfilerSummary getSummary(final int requestedTicks) {
        final int count = Math.min(BUFFER_SIZE, Math.max(1, requestedTicks));
        final long latest = this.currentTickCounter.get();
        if (latest <= 0) {
            return new ProfilerSummary(0, 0, 0, 0, 0, 0, new EnumMap<>(Subsystem.class));
        }

        final int actualCount = (int) Math.min((long) count, latest);
        final double[] totalMsArray = new double[actualCount];
        final double[][] subMsArrays = new double[SUBSYSTEM_COUNT][actualCount];
        double totalSum = 0;
        final double[] subSums = new double[SUBSYSTEM_COUNT];

        int valid = 0;
        for (int i = 0; i < actualCount; i++) {
            final long targetTick = latest - i;
            final int slot = (int) (targetTick % BUFFER_SIZE);
            final TickSnapshot snap = this.ringBuffer.get(slot);
            if (snap != null && snap.tickNumber == targetTick) {
                final double tMs = snap.totalMs();
                totalMsArray[valid] = tMs;
                totalSum += tMs;

                for (int s = 0; s < SUBSYSTEM_COUNT; s++) {
                    final double sMs = snap.subsystemMs(Subsystem.values()[s]);
                    subMsArrays[s][valid] = sMs;
                    subSums[s] += sMs;
                }
                valid++;
            }
        }

        if (valid == 0) {
            return new ProfilerSummary(0, 0, 0, 0, 0, 0, new EnumMap<>(Subsystem.class));
        }

        final double[] trimmedTotals = Arrays.copyOf(totalMsArray, valid);
        Arrays.sort(trimmedTotals);
        final double avgMspt = totalSum / valid;
        final double p50 = percentile(trimmedTotals, 50.0);
        final double p95 = percentile(trimmedTotals, 95.0);
        final double p99 = percentile(trimmedTotals, 99.0);
        final double max = trimmedTotals[valid - 1];

        final Map<Subsystem, SubsystemStats> statsMap = new EnumMap<>(Subsystem.class);
        for (final Subsystem sub : Subsystem.values()) {
            final int sIdx = sub.ordinal();
            final double[] sArr = Arrays.copyOf(subMsArrays[sIdx], valid);
            Arrays.sort(sArr);
            final double sAvg = subSums[sIdx] / valid;
            final double sPct = totalSum > 0 ? (subSums[sIdx] / totalSum) * 100.0 : 0.0;
            final double sP50 = percentile(sArr, 50.0);
            final double sP95 = percentile(sArr, 95.0);
            final double sP99 = percentile(sArr, 99.0);
            final double sMax = sArr[valid - 1];

            statsMap.put(sub, new SubsystemStats(sub, sAvg, sPct, sP50, sP95, sP99, sMax));
        }

        return new ProfilerSummary(valid, avgMspt, p50, p95, p99, max, statsMap);
    }

    private static double percentile(final double[] sorted, final double p) {
        if (sorted.length == 0) return 0.0;
        final int index = (int) Math.ceil((p / 100.0) * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }


    @Override
    public double getRollingMspt() {
        return getSummary(100).averageMspt();
    }

    @Override
    public double getP50Mspt() {
        return getSummary(100).p50Mspt();
    }

    @Override
    public double getP95Mspt() {
        return getSummary(100).p95Mspt();
    }

    @Override
    public double getP99Mspt() {
        return getSummary(100).p99Mspt();
    }

    @Override
    public double getMaxMspt() {
        return getSummary(1200).maxMspt();
    }

    @Override
    public String getReport() {
        final ProfilerSummary summary = getSummary(100);
        final StringBuilder sb = new StringBuilder(1024);
        sb.append(String.format("=== AGC Subsystem Profiler Report (Window: %d ticks) ===\n", summary.sampleCount()));
        sb.append(String.format("MSPT: avg=%.2fms | p50=%.2fms | p95=%.2fms | p99=%.2fms | max=%.2fms\n",
            summary.averageMspt(), summary.p50Mspt(), summary.p95Mspt(), summary.p99Mspt(), summary.maxMspt()));
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(String.format("%-26s %8s %8s %8s %8s %8s %8s\n", "Subsystem", "Share%", "Avg(ms)", "p50", "p95", "p99", "Max"));
        sb.append("--------------------------------------------------------------------------------\n");

        for (final Subsystem sub : Subsystem.values()) {
            final SubsystemStats s = summary.subsystemStats().get(sub);
            if (s != null) {
                sb.append(String.format("%-26s %7.1f%% %8.2f %8.2f %8.2f %8.2f %8.2f\n",
                    sub.displayName(), s.percentageOfTotal(), s.averageMs(), s.p50Ms(), s.p95Ms(), s.p99Ms(), s.maxMs()));
            }
        }
        sb.append("================================================================================");
        return sb.toString();
    }

    @Override
    public String getSubsystemPercentagesJson() {
        final ProfilerSummary summary = getSummary(100);
        final StringBuilder sb = new StringBuilder(512);
        sb.append("{");
        sb.append("\"sampleCount\":").append(summary.sampleCount()).append(",");
        sb.append("\"avgMspt\":").append(String.format("%.2f", summary.averageMspt())).append(",");
        sb.append("\"p95Mspt\":").append(String.format("%.2f", summary.p95Mspt())).append(",");
        sb.append("\"p99Mspt\":").append(String.format("%.2f", summary.p99Mspt())).append(",");
        sb.append("\"subsystems\":{");

        boolean first = true;
        for (final Subsystem sub : Subsystem.values()) {
            final SubsystemStats s = summary.subsystemStats().get(sub);
            if (s != null) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(sub.name()).append("\":{")
                  .append("\"name\":\"").append(sub.displayName()).append("\",")
                  .append("\"percent\":").append(String.format("%.2f", s.percentageOfTotal())).append(",")
                  .append("\"avgMs\":").append(String.format("%.2f", s.averageMs())).append(",")
                  .append("\"p99Ms\":").append(String.format("%.2f", s.p99Ms()))
                  .append("}");
            }
        }
        sb.append("}}");
        return sb.toString();
    }
}
