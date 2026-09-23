package io.papermc.paper.agc.chunk;

import io.papermc.paper.agc.AgcAdaptiveGovernor;
import io.agcmc.agc.api.event.AgcPerformanceLevelEvent.PerformanceLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class AgcChunkGenThrottlerTest {

    @BeforeEach
    public void setup() {
        AgcChunkGenThrottler.get().onTickStart();
        AgcAdaptiveGovernor.get().setManualOverride(PerformanceLevel.LEVEL_0_OPTIMAL);
    }

    @AfterEach
    public void teardown() {
        AgcAdaptiveGovernor.get().setManualOverride(null);
    }

    @Test
    public void testThrottlerGuaranteesGenerationUnderLosslessPolicy() {
        AgcChunkGenThrottler throttler = AgcChunkGenThrottler.get();
        for (int i = 0; i < 100; i++) {
            assertTrue(throttler.canGenerate(), "Must always allow chunk generation to prevent Elytra flight freezes");
        }
    }

    @Test
    public void testThrottlerCongestedPreservesGeneration() {
        AgcAdaptiveGovernor.get().setManualOverride(PerformanceLevel.LEVEL_2_CONGESTED);
        AgcChunkGenThrottler throttler = AgcChunkGenThrottler.get();
        throttler.onTickStart();
        for (int i = 0; i < 50; i++) {
            assertTrue(throttler.canGenerate(), "Must preserve chunk generation even under congested level");
        }
    }

    @Test
    public void testGenRateIsNeverOverridden() {
        // Paper parity (2026-09-21): the old Math.max(rate, 25) silently overrode operator config.
        for (final double rate : new double[] {1.0, 5.0, 24.9, 25.0, 100.0}) {
            org.junit.jupiter.api.Assertions.assertEquals(rate, AgcChunkGenThrottler.get().getEffectiveGenRate(rate),
                "the configured gen rate must pass through untouched");
        }
    }
}
