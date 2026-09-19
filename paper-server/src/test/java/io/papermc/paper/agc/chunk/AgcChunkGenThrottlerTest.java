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
}
