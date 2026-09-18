package io.papermc.paper.agc.profiling;

import io.papermc.paper.agc.AgcStabilityJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Precision Memory and Garbage Collection Telemetry Tracker.
 *
 * <p>Monitors JVM heap dynamics, direct/off-heap memory allocations, Young/Old GC pause metrics,
 * and estimates realtime object allocation rates. Correlates GC pauses with tick stalls and
 * exposes telemetry via JMX and the {@code /agc gc} / {@code /agc memory} commands.</p>
 */
public final class AgcMemoryTracker implements AgcMemoryTrackerMXBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcMemoryTracker.class);
    private static final AgcMemoryTracker INSTANCE = new AgcMemoryTracker();

    private final MemoryMXBean memoryBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final List<BufferPoolMXBean> bufferPoolBeans;

    private final AtomicLong lastAllocSampleTimeNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong lastAllocSampleHeapUsed = new AtomicLong();
    private volatile double estimatedAllocRateMbPerSec = 0.0;

    private final AtomicLong lastObservedGcTimeMillis = new AtomicLong();
    private final AtomicLong totalRecordedGcSpikeTicks = new AtomicLong();

    public static AgcMemoryTracker get() {
        return INSTANCE;
    }

    private AgcMemoryTracker() {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.bufferPoolBeans = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);

        final MemoryUsage heap = this.memoryBean.getHeapMemoryUsage();
        this.lastAllocSampleHeapUsed.set(heap.getUsed());
        this.lastObservedGcTimeMillis.set(getTotalGcPauseMillis());

        registerMBean();
    }

    private void registerMBean() {
        try {
            final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            final ObjectName name = new ObjectName("io.papermc.paper.agc:type=MemoryTracker");
            if (!mbs.isRegistered(name)) {
                mbs.registerMBean(this, name);
                LOGGER.info("[AGC] Memory Tracker JMX MXBean registered successfully.");
            }
        } catch (final Throwable t) {
            LOGGER.warn("[AGC] Could not register Memory Tracker JMX MBean: {}", t.getMessage());
        }
    }

    /**
     * Samples memory state at server tick boundary.
     */
    public void onTick(final long tickNumber, final double tickMspt) {
        final long nowNanos = System.nanoTime();
        final long elapsedNanos = nowNanos - this.lastAllocSampleTimeNanos.get();

        if (elapsedNanos >= 1_000_000_000L) {
            final MemoryUsage heap = this.memoryBean.getHeapMemoryUsage();
            final long currentUsed = heap.getUsed();
            final long prevUsed = this.lastAllocSampleHeapUsed.getAndSet(currentUsed);
            this.lastAllocSampleTimeNanos.set(nowNanos);

            if (currentUsed > prevUsed) {
                final double deltaBytes = currentUsed - prevUsed;
                final double deltaSeconds = elapsedNanos / 1_000_000_000.0;
                this.estimatedAllocRateMbPerSec = (deltaBytes / (1024.0 * 1024.0)) / deltaSeconds;
            }
        }

        final long currentGcTime = getTotalGcPauseMillis();
        final long prevGcTime = this.lastObservedGcTimeMillis.getAndSet(currentGcTime);
        final long gcDelta = currentGcTime - prevGcTime;

        if (gcDelta >= 30) {
            this.totalRecordedGcSpikeTicks.incrementAndGet();
            AgcStabilityJournal.get().record(
                AgcStabilityJournal.EventType.MEMORY_PRESSURE,
                "MemoryTracker",
                String.format("High GC pause detected during tick %d: %dms (Tick MSPT=%.2fms)", tickNumber, gcDelta, tickMspt)
            );
        }
    }


    @Override
    public long getUsedHeapBytes() {
        return this.memoryBean.getHeapMemoryUsage().getUsed();
    }

    @Override
    public long getMaxHeapBytes() {
        return this.memoryBean.getHeapMemoryUsage().getMax();
    }

    @Override
    public double getHeapUsageRatio() {
        final MemoryUsage heap = this.memoryBean.getHeapMemoryUsage();
        final long max = heap.getMax();
        return max > 0 ? (double) heap.getUsed() / (double) max : 0.0;
    }

    @Override
    public long getDirectMemoryUsedBytes() {
        long directBytes = 0;
        for (final BufferPoolMXBean pool : this.bufferPoolBeans) {
            if ("direct".equalsIgnoreCase(pool.getName())) {
                directBytes += pool.getMemoryUsed();
            }
        }
        return directBytes;
    }

    public long getDirectMemoryCapacityBytes() {
        long capacityBytes = 0;
        for (final BufferPoolMXBean pool : this.bufferPoolBeans) {
            if ("direct".equalsIgnoreCase(pool.getName())) {
                capacityBytes += pool.getTotalCapacity();
            }
        }
        return capacityBytes;
    }

    @Override
    public long getTotalGcPauseMillis() {
        long totalTime = 0;
        for (final GarbageCollectorMXBean gc : this.gcBeans) {
            final long time = gc.getCollectionTime();
            if (time > 0) totalTime += time;
        }
        return totalTime;
    }

    @Override
    public long getTotalGcCount() {
        long totalCount = 0;
        for (final GarbageCollectorMXBean gc : this.gcBeans) {
            final long count = gc.getCollectionCount();
            if (count > 0) totalCount += count;
        }
        return totalCount;
    }

    @Override
    public double getEstimatedAllocationRateMbPerSec() {
        return this.estimatedAllocRateMbPerSec;
    }

    public long getTotalRecordedGcSpikeTicks() {
        return this.totalRecordedGcSpikeTicks.get();
    }

    @Override
    public String getMemoryReport() {
        final MemoryUsage heap = this.memoryBean.getHeapMemoryUsage();
        final MemoryUsage nonHeap = this.memoryBean.getNonHeapMemoryUsage();
        final double heapUsedMb = heap.getUsed() / (1024.0 * 1024.0);
        final double heapMaxMb = heap.getMax() / (1024.0 * 1024.0);
        final double directUsedMb = getDirectMemoryUsedBytes() / (1024.0 * 1024.0);
        final double directCapMb = getDirectMemoryCapacityBytes() / (1024.0 * 1024.0);

        final StringBuilder sb = new StringBuilder(1024);
        sb.append("=== AGC Memory & GC Telemetry Report ===\n");
        sb.append(String.format("Heap Usage   : %.1f / %.1f MB (%.1f%%)\n",
            heapUsedMb, heapMaxMb, getHeapUsageRatio() * 100.0));
        sb.append(String.format("Non-Heap Used: %.1f MB\n", nonHeap.getUsed() / (1024.0 * 1024.0)));
        sb.append(String.format("Direct Memory: %.1f MB (Allocated: %.1f MB)\n", directUsedMb, directCapMb));
        sb.append(String.format("Alloc Rate   : ~%.1f MB/s\n", this.estimatedAllocRateMbPerSec));
        sb.append(String.format("GC Total     : %d collections | %dms total pause time\n",
            getTotalGcCount(), getTotalGcPauseMillis()));

        sb.append("---------------- Collectors ----------------\n");
        for (final GarbageCollectorMXBean gc : this.gcBeans) {
            sb.append(String.format("%-20s: %5d runs | %7dms pause\n",
                gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
        }
        sb.append("============================================");
        return sb.toString();
    }

    @Override
    public String getGcStatsJson() {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("{");
        sb.append("\"heapUsedBytes\":").append(getUsedHeapBytes()).append(",");
        sb.append("\"heapMaxBytes\":").append(getMaxHeapBytes()).append(",");
        sb.append("\"heapUsageRatio\":").append(String.format("%.4f", getHeapUsageRatio())).append(",");
        sb.append("\"directMemoryBytes\":").append(getDirectMemoryUsedBytes()).append(",");
        sb.append("\"allocRateMbPerSec\":").append(String.format("%.2f", this.estimatedAllocRateMbPerSec)).append(",");
        sb.append("\"totalGcCount\":").append(getTotalGcCount()).append(",");
        sb.append("\"totalGcPauseMs\":").append(getTotalGcPauseMillis()).append(",");
        sb.append("\"collectors\":[");
        boolean first = true;
        for (final GarbageCollectorMXBean gc : this.gcBeans) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"name\":\"").append(gc.getName()).append("\",")
              .append("\"count\":").append(gc.getCollectionCount()).append(",")
              .append("\"timeMs\":").append(gc.getCollectionTime()).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
