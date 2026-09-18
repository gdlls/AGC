package io.papermc.paper.agc.jvm;

import io.papermc.paper.agc.AgcGcTuningAdvisor;
import io.papermc.paper.agc.profiling.AgcMemoryTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Autonomous Runtime JVM & Garbage Collection Tuner.
 *
 * <p>Continuously monitors Garbage Collector behavior, young/old generation pause durations,
 * and memory allocation rates. Detects GC thrashing and logs automated tuning directives.</p>
 */
public final class AgcJvmTuner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcJvmTuner.class);
    private static final AgcJvmTuner INSTANCE = new AgcJvmTuner();

    public enum GcEngineType {
        GENERATIONAL_ZGC,
        LEGACY_ZGC,
        SHENANDOAH,
        G1GC,
        PARALLEL_GC,
        UNKNOWN
    }

    private final GcEngineType detectedCollector;
    private final AtomicLong gcPressureWarnings = new AtomicLong();

    public static AgcJvmTuner get() {
        return INSTANCE;
    }

    private AgcJvmTuner() {
        this.detectedCollector = detectActiveCollector();
    }

    private static GcEngineType detectActiveCollector() {
        final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (final GarbageCollectorMXBean bean : gcBeans) {
            final String name = bean.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("zgc")) {
                if (name.contains("major") || name.contains("minor") || name.contains("generational")) {
                    return GcEngineType.GENERATIONAL_ZGC;
                }
                return GcEngineType.LEGACY_ZGC;
            }
            if (name.contains("shenandoah")) {
                return GcEngineType.SHENANDOAH;
            }
            if (name.contains("g1")) {
                return GcEngineType.G1GC;
            }
            if (name.contains("parallel") || name.contains("ps")) {
                return GcEngineType.PARALLEL_GC;
            }
        }
        return GcEngineType.UNKNOWN;
    }

    /**
     * Evaluates runtime GC performance and detects latency anomalies.
     *
     * @param memoryTracker Current memory tracker instance
     * @return Health evaluation status
     */
    public GcHealthStatus evaluateHealth(final AgcMemoryTracker memoryTracker) {
        if (memoryTracker == null) {
            return GcHealthStatus.HEALTHY;
        }

        final double heapUsageRatio = memoryTracker.getHeapUsageRatio();
        final long totalPauseMs = memoryTracker.getTotalGcPauseMillis();

        return evaluateHealth(heapUsageRatio, totalPauseMs);
    }

    /**
     * Evaluates runtime GC performance with explicit telemetry arguments.
     *
     * @param heapUsageRatio Current heap usage (0.0 to 1.0)
     * @param lastPauseMs Duration of recent GC pause in ms
     * @return Health evaluation status
     */
    public GcHealthStatus evaluateHealth(final double heapUsageRatio, final double lastPauseMs) {
        if (lastPauseMs > 50.0 || heapUsageRatio > 0.95) {
            this.gcPressureWarnings.incrementAndGet();
            LOGGER.warn("[AGC] Severe GC latency spike detected! Last Pause: {}ms, Heap Usage: {}%. Collector: {}",
                String.format(java.util.Locale.ROOT, "%.2f", lastPauseMs),
                String.format(java.util.Locale.ROOT, "%.1f", heapUsageRatio * 100.0),
                this.detectedCollector);
            return GcHealthStatus.CRITICAL_PRESSURE;
        } else if (lastPauseMs > 15.0 || heapUsageRatio > 0.85) {
            return GcHealthStatus.ELEVATED_LOAD;
        }

        return GcHealthStatus.HEALTHY;
    }

    public GcEngineType getDetectedCollector() {
        return this.detectedCollector;
    }

    public boolean isGenerationalZgc() {
        return this.detectedCollector == GcEngineType.GENERATIONAL_ZGC;
    }

    public String generateTuningReport() {
        return AgcGcTuningAdvisor.get().renderReport();
    }

    public void clear() {
        this.gcPressureWarnings.set(0);
    }

    public TunerMetrics metrics() {
        return new TunerMetrics(
            this.detectedCollector.name(),
            this.gcPressureWarnings.get()
        );
    }

    public enum GcHealthStatus {
        HEALTHY,
        ELEVATED_LOAD,
        CRITICAL_PRESSURE
    }

    public record TunerMetrics(
        String activeCollector,
        long gcPressureWarnings
    ) {
    }
}
