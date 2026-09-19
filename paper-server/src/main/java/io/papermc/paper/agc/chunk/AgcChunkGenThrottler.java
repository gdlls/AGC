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
        // Lossless parity: Always guarantee chunk generation to prevent Elytra flight freezes and transparent world boundaries.
        return true;
    }

    public double getEffectiveGenRate(final double baseRate) {
        return Math.max(baseRate, 25.0);
    }

    public int getGeneratedThisTick() {
        return this.generatedThisTick.get();
    }
}
