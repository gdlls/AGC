package io.papermc.paper.agc.chunk;

import io.papermc.paper.agc.AgcAdaptiveGovernor;
import io.agcmc.agc.api.event.AgcPerformanceLevelEvent.PerformanceLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * AGC — Adaptive Chunk Generation Throttler.
 */
public final class AgcChunkGenThrottler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgcChunkGenThrottler.class);
    private static final AgcChunkGenThrottler INSTANCE = new AgcChunkGenThrottler();

    private final AtomicInteger generatedThisTick = new AtomicInteger(0);

    public static AgcChunkGenThrottler get() {
        return INSTANCE;
    }

    private AgcChunkGenThrottler() {}

    public void onTickStart() {
        this.generatedThisTick.set(0);
    }

    public boolean canGenerate() {
        int budget = 50; // Default budget
        PerformanceLevel level = AgcAdaptiveGovernor.get().getLevel();
        if (level == PerformanceLevel.LEVEL_1_ELEVATED) {
            budget = 25;
        } else if (level == PerformanceLevel.LEVEL_2_CONGESTED) {
            budget = 10;
        } else if (level == PerformanceLevel.LEVEL_3_SEVERE) {
            budget = 2;
        } else if (level == PerformanceLevel.LEVEL_4_CRITICAL) {
            budget = 0;
        }

        return this.generatedThisTick.incrementAndGet() <= budget;
    }

    public double getEffectiveGenRate(final double baseRate) {
        final PerformanceLevel level = AgcAdaptiveGovernor.get().getLevel();
        if (level == PerformanceLevel.LEVEL_1_ELEVATED) {
            return Math.min(baseRate, 20.0);
        } else if (level == PerformanceLevel.LEVEL_2_CONGESTED) {
            return Math.min(baseRate, 10.0);
        } else if (level == PerformanceLevel.LEVEL_3_SEVERE) {
            return Math.min(baseRate, 4.0);
        } else if (level == PerformanceLevel.LEVEL_4_CRITICAL) {
            return 1.0;
        }
        return Math.min(baseRate, 25.0);
    }

    public int getGeneratedThisTick() {
        return this.generatedThisTick.get();
    }
}
