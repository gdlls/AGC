package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcPerformanceGovernor}.
 */
class AgcPerformanceGovernorTest {

    @BeforeEach
    @AfterEach
    void resetGovernor() {
        AgcPerformanceGovernor.get().resetMetrics();
        AgcStabilityJournal.get().clear();
    }

    @Test
    void evaluatesStatesCorrectlyBasedOnTelemetry() {
        final var gov = AgcPerformanceGovernor.get();

        // 1. Healthy: 20ms, 50% memory, 100 players
        assertEquals(AgcPerformanceGovernor.State.HEALTHY, gov.evaluate(20.0, 0.50, 100));

        // 2. Elevated load: 35ms or 300 players
        assertEquals(AgcPerformanceGovernor.State.ELEVATED_LOAD, gov.evaluate(35.0, 0.70, 300));

        // 3. High congestion: 44ms or 500 players or 88% heap
        assertEquals(AgcPerformanceGovernor.State.HIGH_CONGESTION, gov.evaluate(44.0, 0.88, 500));

        // 4. Emergency Guard: 49ms or 93% heap
        assertEquals(AgcPerformanceGovernor.State.EMERGENCY_GUARD, gov.evaluate(49.0, 0.94, 500));

        // 5. Recovery back to Healthy
        assertEquals(AgcPerformanceGovernor.State.HEALTHY, gov.evaluate(18.0, 0.45, 50));

        final var m = gov.metrics();
        assertTrue(m.totalTransitions() >= 4);
    }

    @Test
    void stateTransitionsAreLoggedToStabilityJournal() {
        final var gov = AgcPerformanceGovernor.get();
        gov.evaluate(20.0, 0.50, 50); // Start HEALTHY (no change if starting at HEALTHY)
        gov.evaluate(49.0, 0.95, 500); // Transitions to EMERGENCY_GUARD

        final var entries = AgcStabilityJournal.get().getRecentEntries(10);
        assertFalse(entries.isEmpty());
        assertTrue(entries.get(0).message().contains("EMERGENCY_GUARD"));
    }
}
