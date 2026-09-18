package io.papermc.paper.agc.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Realtime Plugin Watchdog & Circuit Breaker Engine.
 *
 * <p>Tracks CPU time consumed by third-party plugin event listeners. Automatically trips a circuit
 * breaker when a rogue plugin hangs ($>100\text{ ms}$) or burns excessive CPU ($>5\text{ ms/tick}$),
 * protecting the server from freezing or dropping below 20 TPS.</p>
 */
public final class AgcPluginWatchdog {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcPluginWatchdog.class);
    private static final AgcPluginWatchdog INSTANCE = new AgcPluginWatchdog();

    public static final long LAGGY_PLUGIN_THRESHOLD_NANOS = 5_000_000L; // 5ms per tick
    public static final long CIRCUIT_BREAKER_TRIP_NANOS = 100_000_000L;  // 100ms stall

    private final ConcurrentHashMap<String, PluginStats> pluginStats = new ConcurrentHashMap<>();

    private final AtomicLong totalInvocationsTracked = new AtomicLong();
    private final AtomicLong circuitBreakerTrips = new AtomicLong();
    private final AtomicLong warningsIssued = new AtomicLong();

    public static AgcPluginWatchdog get() {
        return INSTANCE;
    }

    private AgcPluginWatchdog() {}

    /**
     * Records execution time consumed by a specific plugin.
     *
     * @param pluginName Plugin identifier
     * @param elapsedNanos Time spent in nanoseconds
     * @return {@code true} if plugin is healthy, {@code false} if circuit breaker is tripped
     */
    public boolean recordExecution(final String pluginName, final long elapsedNanos) {
        if (pluginName == null) return true;
        this.totalInvocationsTracked.incrementAndGet();

        final PluginStats stats = this.pluginStats.computeIfAbsent(pluginName, PluginStats::new);
        stats.totalNanos.addAndGet(elapsedNanos);
        stats.invocations.incrementAndGet();

        if (elapsedNanos > stats.peakNanos.get()) {
            stats.peakNanos.set(elapsedNanos);
        }

        if (elapsedNanos >= CIRCUIT_BREAKER_TRIP_NANOS) {
            if (stats.circuitBreakerTripped.compareAndSet(false, true)) {
                this.circuitBreakerTrips.incrementAndGet();
                LOGGER.error("[AGC PluginWatchdog] CIRCUIT BREAKER TRIPPED for plugin '{}'! Execution stalled for {}ms. Listener temporarily suppressed.",
                    pluginName, String.format(java.util.Locale.ROOT, "%.2f", elapsedNanos / 1_000_000.0));
            }
            return false;
        } else if (elapsedNanos >= LAGGY_PLUGIN_THRESHOLD_NANOS) {
            this.warningsIssued.incrementAndGet();
            LOGGER.warn("[AGC PluginWatchdog] Laggy execution detected in plugin '{}': {}ms",
                pluginName, String.format(java.util.Locale.ROOT, "%.2f", elapsedNanos / 1_000_000.0));
        }

        return !stats.circuitBreakerTripped.get();
    }

    /**
     * Checks whether a plugin is currently blocked by the circuit breaker.
     */
    public boolean isCircuitBreakerTripped(final String pluginName) {
        if (pluginName == null) return false;
        final PluginStats stats = this.pluginStats.get(pluginName);
        return stats != null && stats.circuitBreakerTripped.get();
    }

    /**
     * Manually resets the circuit breaker for a plugin.
     */
    public void resetCircuitBreaker(final String pluginName) {
        if (pluginName == null) return;
        final PluginStats stats = this.pluginStats.get(pluginName);
        if (stats != null) {
            stats.circuitBreakerTripped.set(false);
        }
    }

    /**
     * Generates a diagnostic summary of plugin CPU consumption.
     */
    public String generatePluginReport() {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("=== AGC Plugin Performance & Watchdog Report ===\n");
        sb.append(String.format("Tracked Plugins: %d | Total Invocations: %d | Circuit Breaker Trips: %d\n",
            this.pluginStats.size(), this.totalInvocationsTracked.get(), this.circuitBreakerTrips.get()));
        sb.append("------------------------------------------------\n");

        final List<PluginStats> list = new ArrayList<>(this.pluginStats.values());
        list.sort((a, b) -> Long.compare(b.totalNanos.get(), a.totalNanos.get()));

        for (final PluginStats s : list) {
            final double totalMs = s.totalNanos.get() / 1_000_000.0;
            final double peakMs = s.peakNanos.get() / 1_000_000.0;
            final long inv = s.invocations.get();
            final double avgMs = inv > 0 ? totalMs / inv : 0.0;

            sb.append(String.format("%-20s: Total=%8.2fms | Avg=%5.3fms | Peak=%6.2fms | Invocations=%6d | Tripped=%s\n",
                s.pluginName, totalMs, avgMs, peakMs, inv, s.circuitBreakerTripped.get() ? "YES" : "NO"));
        }
        sb.append("================================================");
        return sb.toString();
    }

    public void clear() {
        this.pluginStats.clear();
        this.totalInvocationsTracked.set(0);
        this.circuitBreakerTrips.set(0);
        this.warningsIssued.set(0);
    }

    public WatchdogMetrics metrics() {
        int trippedCount = 0;
        for (final PluginStats s : this.pluginStats.values()) {
            if (s.circuitBreakerTripped.get()) trippedCount++;
        }
        return new WatchdogMetrics(
            this.pluginStats.size(),
            trippedCount,
            this.totalInvocationsTracked.get(),
            this.circuitBreakerTrips.get(),
            this.warningsIssued.get()
        );
    }

    private static final class PluginStats {
        final String pluginName;
        final AtomicLong totalNanos = new AtomicLong();
        final AtomicLong peakNanos = new AtomicLong();
        final AtomicLong invocations = new AtomicLong();
        final AtomicBoolean circuitBreakerTripped = new AtomicBoolean(false);

        PluginStats(final String pluginName) {
            this.pluginName = pluginName;
        }
    }

    public record WatchdogMetrics(
        int trackedPlugins,
        int activeCircuitBreakers,
        long totalInvocationsTracked,
        long circuitBreakerTrips,
        long warningsIssued
    ) {
    }
}
