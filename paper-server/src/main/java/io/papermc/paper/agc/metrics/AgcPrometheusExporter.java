package io.papermc.paper.agc.metrics;

import io.papermc.paper.agc.AgcAdaptiveGovernor;
import io.papermc.paper.agc.AgcMetrics;
import io.papermc.paper.agc.AgcWorldHibernationEngine;
import io.papermc.paper.agc.network.AgcConnectionManager;
import io.papermc.paper.agc.profiling.AgcMemoryTracker;
import io.papermc.paper.agc.profiling.AgcTickProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * AGC — Prometheus & OpenMetrics High-Performance Telemetry Exporter.
 *
 * <p>Formats realtime AGC engine metrics into standard Prometheus exposition format for scraping
 * by Grafana, VictoriaMetrics, and Prometheus observability stacks.</p>
 */
public final class AgcPrometheusExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcPrometheusExporter.class);
    private static final AgcPrometheusExporter INSTANCE = new AgcPrometheusExporter();

    public static AgcPrometheusExporter get() {
        return INSTANCE;
    }

    private AgcPrometheusExporter() {}

    /**
     * Generates Prometheus exposition formatted text.
     *
     * @return Formatted metric payload
     */
    public String exportPrometheusMetrics() {
        final StringBuilder sb = new StringBuilder(2048);

        final var profiler = AgcTickProfiler.get();
        final var mem = AgcMemoryTracker.get();
        final var gov = AgcAdaptiveGovernor.get();
        final var hib = AgcWorldHibernationEngine.get().metrics();
        final var conn = AgcConnectionManager.get().metrics();
        final var metricsSnapshot = AgcMetrics.snapshot();

        final double rollingMspt = profiler.getRollingMspt();
        final double estimatedTps = rollingMspt > 0 ? Math.min(20.0, 1000.0 / Math.max(1.0, rollingMspt)) : 20.0;

        appendHelpType(sb, "agc_server_tps", "Current estimated server ticks per second", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_server_tps %.2f\n", estimatedTps));

        appendHelpType(sb, "agc_server_mspt_rolling", "Rolling average tick duration in milliseconds", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_server_mspt_rolling %.3f\n", rollingMspt));

        appendHelpType(sb, "agc_server_mspt_p50", "50th percentile tick duration in milliseconds", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_server_mspt_p50 %.3f\n", profiler.getP50Mspt()));

        appendHelpType(sb, "agc_server_mspt_p95", "95th percentile tick duration in milliseconds", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_server_mspt_p95 %.3f\n", profiler.getP95Mspt()));

        appendHelpType(sb, "agc_server_mspt_p99", "99th percentile tick duration in milliseconds", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_server_mspt_p99 %.3f\n", profiler.getP99Mspt()));

        appendHelpType(sb, "agc_adaptive_throttle_level", "Current AGC governor throttle level (0=Optimal..4=Critical)", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_adaptive_throttle_level %d\n", gov.getLevel().ordinal()));

        appendHelpType(sb, "agc_worlds_total", "Total managed world count", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_worlds_total %d\n", hib.totalTrackedWorlds()));

        appendHelpType(sb, "agc_worlds_active", "Active ticking worlds (HOT)", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_worlds_active %d\n", hib.activeWorlds()));

        appendHelpType(sb, "agc_worlds_hibernating", "Sleeping worlds (WARM)", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_worlds_hibernating %d\n", hib.warmHibernatingWorlds()));

        appendHelpType(sb, "agc_worlds_cold", "Cold dormant unloaded worlds (COLD)", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_worlds_cold %d\n", hib.coldDormantWorlds()));

        appendHelpType(sb, "agc_jvm_heap_used_bytes", "Used JVM heap memory in bytes", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_jvm_heap_used_bytes %d\n", mem.getUsedHeapBytes()));

        appendHelpType(sb, "agc_jvm_heap_max_bytes", "Maximum JVM heap memory in bytes", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_jvm_heap_max_bytes %d\n", mem.getMaxHeapBytes()));

        appendHelpType(sb, "agc_jvm_direct_memory_bytes", "Direct off-heap allocated memory in bytes", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_jvm_direct_memory_bytes %d\n", mem.getDirectMemoryUsedBytes()));

        appendHelpType(sb, "agc_jvm_gc_pause_millis_total", "Total cumulative GC pause time in milliseconds", "counter");
        sb.append(String.format(Locale.ROOT, "agc_jvm_gc_pause_millis_total %d\n", mem.getTotalGcPauseMillis()));

        appendHelpType(sb, "agc_network_workers", "Active Netty event loop worker threads", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_network_workers %d\n", conn.optimalWorkerThreads()));

        appendHelpType(sb, "agc_network_connections_active", "Active client network connections", "gauge");
        sb.append(String.format(Locale.ROOT, "agc_network_connections_active %d\n", conn.activeConnections()));

        appendHelpType(sb, "agc_network_bytes_sent_total", "Total network bytes transmitted", "counter");
        sb.append(String.format(Locale.ROOT, "agc_network_bytes_sent_total %d\n", metricsSnapshot.packetBytesSent()));

        return sb.toString();
    }

    private static void appendHelpType(final StringBuilder sb, final String name, final String help, final String type) {
        sb.append("# HELP ").append(name).append(" ").append(help).append("\n");
        sb.append("# TYPE ").append(name).append(" ").append(type).append("\n");
    }
}
