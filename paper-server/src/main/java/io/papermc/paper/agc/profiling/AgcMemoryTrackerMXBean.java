package io.papermc.paper.agc.profiling;

/**
 * JMX MXBean interface for AGC Memory and GC Telemetry Tracker.
 */
public interface AgcMemoryTrackerMXBean {

    long getUsedHeapBytes();

    long getMaxHeapBytes();

    double getHeapUsageRatio();

    long getDirectMemoryUsedBytes();

    long getTotalGcPauseMillis();

    long getTotalGcCount();

    double getEstimatedAllocationRateMbPerSec();

    String getMemoryReport();

    String getGcStatsJson();
}
