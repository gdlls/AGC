package io.papermc.paper.agc.chunk;

import io.papermc.paper.agc.AgcAdaptiveGovernor;
import io.agcmc.agc.api.event.AgcPerformanceLevelEvent.PerformanceLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * AGC — Chunk generation rate honesty shim (2026-09-21).
 *
 * <p>This used to be an "adaptive throttler" whose {@code canGenerate()} was hardwired to
 * {@code true} (a no-op gate wired into the chunk loader) and whose {@code getEffectiveGenRate()}
 * silently raised any operator-configured generation rate below 25 back up to 25 — overriding the
 * operator's own paper config. Both behaviours are gone: chunk generation rates are now owned
 * exclusively by the Paper configuration ({@code chunks-auto-send / gen rates}), exactly like
 * Paper. The class remains only as a passive counter so existing telemetry keeps compiling;
 * it no longer influences any rate or gate.</p>
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
        // Honest gate: always true. There is no lossless condition under which the server should
        // refuse to generate a chunk the player is standing in; Paper owns generation budgets.
        return true;
    }

    public double getEffectiveGenRate(final double baseRate) {
        // Paper-parity: return the configured rate untouched. The old Math.max(baseRate, 25.0)
        // overrode operators who deliberately configured a lower rate.
        return baseRate;
    }

    public int getGeneratedThisTick() {
        return this.generatedThisTick.get();
    }
}
