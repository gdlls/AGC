package io.papermc.paper.agc.profiling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcTickProfiler}.
 */
public class AgcTickProfilerTest {

    @Test
    void recordsTicksAndComputesPercentiles() {
        final AgcTickProfiler profiler = AgcTickProfiler.get();

        // Record 100 sample ticks with known variations
        for (int i = 1; i <= 100; i++) {
            profiler.startTick(i);
            profiler.recordSubsystem(AgcTickProfiler.Subsystem.WORLD_TICK, 5_000_000L);     // 5ms
            profiler.recordSubsystem(AgcTickProfiler.Subsystem.ENTITY_TICK, 10_000_000L);   // 10ms
            profiler.recordSubsystem(AgcTickProfiler.Subsystem.CHUNK_LOAD, 3_000_000L);     // 3ms
            profiler.recordSubsystem(AgcTickProfiler.Subsystem.NETWORK, 2_000_000L);        // 2ms
            // Add a spike on tick 50
            final long totalNanos = (i == 50) ? 50_000_000L : 20_000_000L;
            profiler.endTick(i, totalNanos);
        }

        final AgcTickProfiler.ProfilerSummary summary = profiler.getSummary(100);
        assertNotNull(summary);
        assertTrue(summary.sampleCount() > 0);
        assertTrue(summary.averageMspt() >= 20.0);
        assertTrue(summary.maxMspt() >= 50.0);

        // Check subsystem breakdown
        final var worldStats = summary.subsystemStats().get(AgcTickProfiler.Subsystem.WORLD_TICK);
        assertNotNull(worldStats);
        assertTrue(worldStats.averageMs() >= 4.9 && worldStats.averageMs() <= 5.1);

        final var entityStats = summary.subsystemStats().get(AgcTickProfiler.Subsystem.ENTITY_TICK);
        assertNotNull(entityStats);
        assertTrue(entityStats.averageMs() >= 9.9 && entityStats.averageMs() <= 10.1);
        assertTrue(entityStats.percentageOfTotal() > 40.0);

        // Check report strings
        final String report = profiler.getReport();
        assertNotNull(report);
        assertTrue(report.contains("AGC Subsystem Profiler Report"));
        assertTrue(report.contains("Entities"));

        final String json = profiler.getSubsystemPercentagesJson();
        assertNotNull(json);
        assertTrue(json.contains("\"avgMspt\""));
        assertTrue(json.contains("ENTITY_TICK"));
    }

    @Test
    void sampleScopingMeasuresDuration() {
        final AgcTickProfiler profiler = AgcTickProfiler.get();
        profiler.startTick(1001);

        try (final AgcTickProfiler.Sample sample = profiler.startSample(AgcTickProfiler.Subsystem.PLUGIN_EVENT)) {
            // Simulate minimal work
            Thread.onSpinWait();
        }

        profiler.endTick(1001, 1_000_000L);
        final AgcTickProfiler.ProfilerSummary summary = profiler.getSummary(1);
        assertNotNull(summary);
        final var pluginStats = summary.subsystemStats().get(AgcTickProfiler.Subsystem.PLUGIN_EVENT);
        assertNotNull(pluginStats);
    }
}
