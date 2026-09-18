package io.papermc.paper.agc.profiling;

/**
 * JMX MXBean interface for AGC Tick Profiler.
 */
public interface AgcTickProfilerMXBean {

    /**
     * Rolling average MSPT over the last 100 ticks.
     */
    double getRollingMspt();

    /**
     * Estimated 50th percentile (median) MSPT over the last 100 ticks.
     */
    double getP50Mspt();

    /**
     * Estimated 95th percentile MSPT over the last 100 ticks.
     */
    double getP95Mspt();

    /**
     * Estimated 99th percentile MSPT over the last 100 ticks.
     */
    double getP99Mspt();

    /**
     * Maximum MSPT observed in the recent 1200 ticks.
     */
    double getMaxMspt();

    /**
     * Human-readable breakdown report of subsystem execution times.
     */
    String getReport();

    /**
     * JSON formatted representation of recent subsystem performance.
     */
    String getSubsystemPercentagesJson();
}
