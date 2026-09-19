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

    @Test
    void runTriEngine500Players50WorldsSimulation() {
        final var benchmark = new AgcMassiveStressBenchmark();
        final var config = AgcMassiveStressBenchmark.BenchmarkConfig.createDefault500p50w();

        final var triReport = benchmark.runTriEngineBenchmark(config);

        System.out.println(triReport.formatSummaryTable());

        assertNotNull(triReport);
        assertNotNull(triReport.vanilla());
        assertNotNull(triReport.paper());
        assertNotNull(triReport.agc());
        assertTrue(triReport.agc().targetTpsMet(), "AGC must maintain 20.0 TPS");

        // Vanilla and Paper must collapse below 20 TPS
        assertTrue(triReport.vanilla().effectiveTps() < 20.0,
            "Vanilla must collapse under 20 TPS (got " + triReport.vanilla().effectiveTps() + ")");
        assertTrue(triReport.paper().effectiveTps() < 20.0,
            "Paper must collapse under 20 TPS (got " + triReport.paper().effectiveTps() + ")");

        // AGC must outperform Vanilla and Paper in MSPT
        assertTrue(triReport.agc().averageMspt() < triReport.vanilla().averageMspt(),
            "AGC MSPT (" + triReport.agc().averageMspt() + ") must be lower than Vanilla (" + triReport.vanilla().averageMspt() + ")");
        assertTrue(triReport.agc().averageMspt() < triReport.paper().averageMspt(),
            "AGC MSPT (" + triReport.agc().averageMspt() + ") must be lower than Paper (" + triReport.paper().averageMspt() + ")");

        // AGC must save more serializations than Vanilla/Paper
        assertTrue(triReport.agc().serializationsSaved() > triReport.vanilla().serializationsSaved(),
            "AGC must save more serializations than Vanilla");
        assertTrue(triReport.agc().serializationsSaved() > triReport.paper().serializationsSaved(),
            "AGC must save more serializations than Paper");

        // AGC must skip more entity AI goals than Vanilla
        assertTrue(triReport.agc().entityGoalsSkipped() > triReport.vanilla().entityGoalsSkipped(),
            "AGC must skip more entity AI goals than Vanilla");

        // AGC must save world ticks via hibernation
        assertTrue(triReport.agc().worldTicksSaved() > 0,
            "AGC must save world ticks via hibernation");
        assertEquals(0, triReport.vanilla().worldTicksSaved(),
            "Vanilla must not save any world ticks (no hibernation)");
        assertEquals(0, triReport.paper().worldTicksSaved(),
            "Paper must not save any world ticks (no hibernation)");
    }
}
