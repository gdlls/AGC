package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcHierarchicalActivationRangeV3Test {

    @BeforeEach
    void setUp() {
        AgcHierarchicalActivationRangeV3.get().resetMetrics();
    }

    @Test
    void testTierEvaluationAndFrequencyGating() {
        // Distance 25m -> Tier 0 (Full Combat / Proximity / Visual Range <= 48m)
        final AgcHierarchicalActivationRangeV3.EarTier tier0 =
            AgcHierarchicalActivationRangeV3.get().evaluateTier(625.0, false, false);
        assertEquals(AgcHierarchicalActivationRangeV3.EarTier.TIER_0_COMBAT_FULL, tier0);
        assertTrue(AgcHierarchicalActivationRangeV3.get().shouldTick(tier0, 1));
        assertTrue(AgcHierarchicalActivationRangeV3.get().shouldTick(tier0, 2));

        // Distance 55m -> Tier 1 (Standard Tick 48m ~ 64m)
        final AgcHierarchicalActivationRangeV3.EarTier tier1 =
            AgcHierarchicalActivationRangeV3.get().evaluateTier(3025.0, false, false);
        assertEquals(AgcHierarchicalActivationRangeV3.EarTier.TIER_1_STANDARD, tier1);
        assertTrue(AgcHierarchicalActivationRangeV3.get().shouldTick(tier1, 1));

        // Distance 75m -> Tier 2 (Reduced Cadence 10Hz 64m ~ 96m)
        final AgcHierarchicalActivationRangeV3.EarTier tier2 =
            AgcHierarchicalActivationRangeV3.get().evaluateTier(5625.0, false, false);
        assertEquals(AgcHierarchicalActivationRangeV3.EarTier.TIER_2_REDUCED, tier2);
        assertTrue(AgcHierarchicalActivationRangeV3.get().shouldTick(tier2, 2));
        assertFalse(AgcHierarchicalActivationRangeV3.get().shouldTick(tier2, 3));

        // Distance 110m -> Tier 3 (Minimal Cadence 5Hz 96m ~ 128m)
        final AgcHierarchicalActivationRangeV3.EarTier tier3 =
            AgcHierarchicalActivationRangeV3.get().evaluateTier(12100.0, false, false);
        assertEquals(AgcHierarchicalActivationRangeV3.EarTier.TIER_3_MINIMAL, tier3);
        assertTrue(AgcHierarchicalActivationRangeV3.get().shouldTick(tier3, 4));
        assertFalse(AgcHierarchicalActivationRangeV3.get().shouldTick(tier3, 5));

        // Distance 150m -> Tier 4 (Frozen / Inactive 1Hz > 128m)
        final AgcHierarchicalActivationRangeV3.EarTier tier4 =
            AgcHierarchicalActivationRangeV3.get().evaluateTier(22500.0, false, false);
        assertEquals(AgcHierarchicalActivationRangeV3.EarTier.TIER_4_FROZEN, tier4);
        assertTrue(AgcHierarchicalActivationRangeV3.get().shouldTick(tier4, 20));
        assertFalse(AgcHierarchicalActivationRangeV3.get().shouldTick(tier4, 21));

        final AgcHierarchicalActivationRangeV3.EarMetrics metrics =
            AgcHierarchicalActivationRangeV3.get().metrics();
        assertEquals(1, metrics.tier0Count());
        assertEquals(1, metrics.tier1Count());
        assertEquals(1, metrics.tier2Count());
        assertEquals(1, metrics.tier3Count());
        assertEquals(1, metrics.tier4Count());
        assertEquals(5, metrics.totalEvaluations());
        assertTrue(metrics.brainTicksSaved() > 0);
    }
}
