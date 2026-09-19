package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 10 — Massive Stress Benchmark Test for 500+ Players across 50+ Worlds.
 */
class AgcMassiveStressBenchmarkTest {

    @Test
    void runMassive500Players50WorldsSimulation() {
        final var benchmark = new AgcMassiveStressBenchmark();
        final var config = AgcMassiveStressBenchmark.BenchmarkConfig.createDefault500p50w();

        final var report = benchmark.runBenchmark(config);

        System.out.println(report.formatSummary());

        // Assertions verifying core objective achievement
        assertEquals(50, report.totalWorlds());
        assertEquals(10, report.activeWorlds());
        assertEquals(40, report.hibernatingWorlds());
        assertEquals(500, report.totalPlayers());
        assertEquals(5000, report.totalEntities());
        assertEquals(50, report.ticksSimulated());

        // Performance & Optimization Assertions
        assertTrue(report.averageMspt() < 25.0, "Average MSPT must be under 25.0ms, was " + report.averageMspt());
        assertTrue(report.effectiveTps() >= 19.5, "Effective TPS must be at least 19.5, was " + report.effectiveTps());
        assertTrue(report.worldTicksSaved() > 0, "World ticks must be saved via hibernation");
        assertTrue(report.serializationsSaved() > 0, "Packet serializations must be saved via zero-copy broadcast");
        assertTrue(report.entityGoalsSkipped() > 0, "Entity AI goals must be skipped via EAR 2.0 & batching");
        assertTrue(report.hotObjectsReused() > 0, "Hot objects must be recycled without heap allocations");
        assertTrue(report.targetTpsMet(), "Target TPS of 20.0 must be met under full 500 players 50 worlds load");
    }

}
