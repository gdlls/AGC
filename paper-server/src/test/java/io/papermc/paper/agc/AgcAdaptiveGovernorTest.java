package io.papermc.paper.agc;

import io.agcmc.agc.api.event.AgcPerformanceLevelEvent.PerformanceLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcAdaptiveGovernor}.
 */
public class AgcAdaptiveGovernorTest {

    @BeforeEach
    @AfterEach
    void resetGovernor() {
        final var gov = AgcAdaptiveGovernor.get();
        gov.setManualOverride(null);
        AgcStabilityJournal.get().clear();
    }

    @Test
    void verifiesAdaptiveLevelTransitionsAndMultipliers() {
        final var gov = AgcAdaptiveGovernor.get();

        // 1. Initial State: LEVEL_0_OPTIMAL
        assertEquals(PerformanceLevel.LEVEL_0_OPTIMAL, gov.getLevel());
        assertEquals(1.0, gov.getEntityActivationMultiplier());
        assertEquals(1, gov.getBlockEntityTickInterval());
        assertTrue(gov.isChunkGenAllowed());
        assertTrue(gov.isMobSpawningAllowed());

        // 2. Simulate Sustained Degradation to Level 1 (Elevated)
        for (int i = 0; i < 6; i++) {
            gov.evaluate(36.0, 19.0, 0.80, 200);
        }
        assertEquals(PerformanceLevel.LEVEL_1_ELEVATED, gov.getLevel());
        assertEquals(0.80, gov.getEntityActivationMultiplier());

        // 3. Simulate Critical Emergency (Level 4) -> Immediate escalation
        gov.evaluate(60.0, 10.0, 0.96, 1000);
        assertEquals(PerformanceLevel.LEVEL_4_CRITICAL, gov.getLevel());
        assertEquals(0.35, gov.getEntityActivationMultiplier());
        assertEquals(4, gov.getBlockEntityTickInterval());
        assertFalse(gov.isChunkGenAllowed());
        assertFalse(gov.isMobSpawningAllowed());

        // 4. Test Manual Override
        gov.setManualOverride(PerformanceLevel.LEVEL_2_CONGESTED);
        assertTrue(gov.isManualOverrideActive());
        assertEquals(PerformanceLevel.LEVEL_2_CONGESTED, gov.getLevel());

        // Even with healthy metrics, override holds
        gov.evaluate(15.0, 20.0, 0.40, 50);
        assertEquals(PerformanceLevel.LEVEL_2_CONGESTED, gov.getLevel());

        // Reset override
        gov.setManualOverride(null);
        assertFalse(gov.isManualOverrideActive());
    }
}
