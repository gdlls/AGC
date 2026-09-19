package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcUltraScaleStressBenchmarkTest {

    @Test
    void testTarget5000CCU500WorldsSimulation() {
        final AgcUltraScaleStressBenchmark.UltraConfig config =
            AgcUltraScaleStressBenchmark.UltraConfig.createTarget5000CCU500Worlds();

        final AgcUltraScaleStressBenchmark.UltraReport report =
            AgcUltraScaleStressBenchmark.runSimulation(config);

        System.out.println(report.formatSummary());

        assertNotNull(report);
        assertTrue(report.targetSloMet(), "5,000 CCU x 500 Worlds simulation must meet 20 TPS SLO");
        assertTrue(report.averageMspt() < 25.0, "Average MSPT must be under 25.0ms (got " + report.averageMspt() + "ms)");
        assertEquals(20.0, report.effectiveTps(), 0.01);
        assertEquals(500, report.totalWorlds());
        assertEquals(5000, report.totalPlayers());
        assertEquals(50000, report.totalEntities());
        assertTrue(report.worldTicksSavedByHibernation() > 0);
        assertTrue(report.zeroCopyBroadcastsSaved() > 0);
        assertTrue(report.stmTransactionsCommitted() > 0);
    }

    @Test
    void testTriEngine5000CCU500WorldsSimulation() {
        final AgcUltraScaleStressBenchmark.UltraConfig config =
            AgcUltraScaleStressBenchmark.UltraConfig.createTarget5000CCU500Worlds();

        final AgcUltraScaleStressBenchmark.TriEngineUltraReport triReport =
            AgcUltraScaleStressBenchmark.runTriEngineSimulation(config);

        System.out.println(triReport.formatSummaryTable());

        assertNotNull(triReport);
        assertNotNull(triReport.vanilla());
        assertNotNull(triReport.paper());
        assertNotNull(triReport.agc());
        assertTrue(triReport.agc().targetSloMet(), "AGC must meet 20.0 TPS SLO");

        // AGC must have lower MSPT than both Vanilla and Paper
        assertTrue(triReport.agc().averageMspt() < triReport.vanilla().averageMspt(),
            "AGC MSPT (" + triReport.agc().averageMspt() + ") must be lower than Vanilla (" + triReport.vanilla().averageMspt() + ")");
        assertTrue(triReport.agc().averageMspt() < triReport.paper().averageMspt(),
            "AGC MSPT (" + triReport.agc().averageMspt() + ") must be lower than Paper (" + triReport.paper().averageMspt() + ")");
    }

    @Test
    void test1000CCUDenseWildernessRoaming() {
        final AgcUltraScaleStressBenchmark.UltraConfig config =
            AgcUltraScaleStressBenchmark.UltraConfig.create1000CCUDenseWilderness();

        final AgcUltraScaleStressBenchmark.UltraReport report =
            AgcUltraScaleStressBenchmark.runSimulation(config);

        System.out.println(report.formatSummary());

        assertNotNull(report);
        assertTrue(report.targetSloMet(), "1,000 CCU Dense Wilderness must meet 20 TPS SLO");
        assertTrue(report.averageMspt() < 25.0, "Average MSPT must be under 25.0ms (got " + report.averageMspt() + "ms)");
        assertEquals(20.0, report.effectiveTps(), 0.01);
    }

    @Test
    void testTriEngine1000CCUDenseWildernessRoaming() {
        final AgcUltraScaleStressBenchmark.TriEngineUltraReport triReport =
            AgcUltraScaleStressBenchmark.runTriEngineSimulation(
                AgcUltraScaleStressBenchmark.UltraConfig.create1000CCUDenseWilderness());

        System.out.println(triReport.formatSummaryTable());
        assertTrue(triReport.agc().averageMspt() < triReport.vanilla().averageMspt(),
            "AGC MSPT must be lower than Vanilla");
        assertTrue(triReport.agc().averageMspt() <= triReport.paper().averageMspt() * 1.5,
            "AGC MSPT must be competitive with or outperform Paper");
    }

    @Test
    void test1000CCUScatteredChunkLoading() {
        final AgcUltraScaleStressBenchmark.UltraConfig config =
            AgcUltraScaleStressBenchmark.UltraConfig.create1000CCUScatteredChunkLoading();

        final AgcUltraScaleStressBenchmark.UltraReport report =
            AgcUltraScaleStressBenchmark.runSimulation(config);

        System.out.println(report.formatSummary());

        assertNotNull(report);
        assertTrue(report.targetSloMet(), "1,000 CCU Scattered Chunk Loading must meet 20 TPS SLO");
    }

    @Test
    void testTriEngine1000CCUScatteredChunkLoading() {
        final AgcUltraScaleStressBenchmark.TriEngineUltraReport triReport =
            AgcUltraScaleStressBenchmark.runTriEngineSimulation(
                AgcUltraScaleStressBenchmark.UltraConfig.create1000CCUScatteredChunkLoading());

        System.out.println(triReport.formatSummaryTable());
        assertTrue(triReport.agc().targetSloMet());
        assertTrue(triReport.agc().averageMspt() < triReport.vanilla().averageMspt());
    }

    @Test
    void test1000CCUNormalWildernessSurvival() {
        final AgcUltraScaleStressBenchmark.UltraConfig config =
            AgcUltraScaleStressBenchmark.UltraConfig.create1000CCUNormalSurvival();

        final AgcUltraScaleStressBenchmark.UltraReport report =
            AgcUltraScaleStressBenchmark.runSimulation(config);

        System.out.println(report.formatSummary());

        assertNotNull(report);
        assertTrue(report.targetSloMet(), "1,000 CCU Normal Survival must meet 20 TPS SLO");
    }

    @Test
    void testTriEngine1000CCUNormalSurvival() {
        final AgcUltraScaleStressBenchmark.TriEngineUltraReport triReport =
            AgcUltraScaleStressBenchmark.runTriEngineSimulation(
                AgcUltraScaleStressBenchmark.UltraConfig.create1000CCUNormalSurvival());

        System.out.println(triReport.formatSummaryTable());
        assertTrue(triReport.agc().targetSloMet());
        assertTrue(triReport.agc().averageMspt() < triReport.vanilla().averageMspt());
    }

    @Test
    void test1000CCUMassCombatStorm() {
        final AgcUltraScaleStressBenchmark.UltraConfig config =
            AgcUltraScaleStressBenchmark.UltraConfig.create1000CCUMassCombatStorm();

        final AgcUltraScaleStressBenchmark.UltraReport report =
            AgcUltraScaleStressBenchmark.runSimulation(config);

        System.out.println(report.formatSummary());

        assertNotNull(report);
        assertTrue(report.targetSloMet(), "1,000 CCU Mass Combat Storm must meet 20 TPS SLO");
    }

    @Test
    void testTriEngine1000CCUMassCombatStorm() {
        final AgcUltraScaleStressBenchmark.TriEngineUltraReport triReport =
            AgcUltraScaleStressBenchmark.runTriEngineSimulation(
                AgcUltraScaleStressBenchmark.UltraConfig.create1000CCUMassCombatStorm());

        System.out.println(triReport.formatSummaryTable());
        assertTrue(triReport.agc().averageMspt() < triReport.vanilla().averageMspt(),
            "AGC MSPT must be lower than Vanilla");
        assertTrue(triReport.agc().averageMspt() <= triReport.paper().averageMspt() * 1.5,
            "AGC MSPT must be competitive with or outperform Paper");
    }
}
