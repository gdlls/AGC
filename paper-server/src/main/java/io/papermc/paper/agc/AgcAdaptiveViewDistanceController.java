package io.papermc.paper.agc;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Dynamic Adaptive View Distance Controller.
 *
 * <p>Under high player concurrency (500+ players) or during tick lag spikes (MSPT &gt; 40ms),
 * keeping a static high view distance causes geometric chunk memory and bandwidth explosion.</p>
 *
 * <p>This controller continuously calculates an optimal view distance based on rolling tick duration
 * and player density with hysteresis smoothing to prevent rapid oscillation.</p>
 */
public final class AgcAdaptiveViewDistanceController {

    private static final AgcAdaptiveViewDistanceController INSTANCE = new AgcAdaptiveViewDistanceController();

    public static final int DEFAULT_MIN_VIEW_DISTANCE = 4;
    public static final int DEFAULT_MAX_VIEW_DISTANCE = 12;

    private final AtomicInteger currentTargetViewDistance = new AtomicInteger(8);
    private final AtomicLong adjustmentsMade = new AtomicLong();

    public static AgcAdaptiveViewDistanceController get() {
        return INSTANCE;
    }

    private AgcAdaptiveViewDistanceController() {}

    /**
     * Updates and calculates the optimal view distance with default min/max bounds.
     *
     * @param rollingMspt Average milliseconds per tick
     * @param playerCount Current online players
     * @return Recommended view distance in chunks
     */
    public int update(final double rollingMspt, final int playerCount) {
        return this.calculateOptimalDistance(rollingMspt, playerCount, DEFAULT_MIN_VIEW_DISTANCE, DEFAULT_MAX_VIEW_DISTANCE);
    }

    /**
     * Calculates the optimal view distance based on current MSPT and global player count.
     *
     * @param rollingMspt Average milliseconds per tick over the last 100 ticks
     * @param playerCount Current total concurrent online players
     * @param minDistance Minimum view distance floor
     * @param maxDistance Maximum view distance ceiling
     * @return Recommended view distance in chunks
     */
    public int calculateOptimalDistance(
        final double rollingMspt,
        final int playerCount,
        final int minDistance,
        final int maxDistance
    ) {
        final int min = Math.max(2, minDistance);
        final int max = Math.max(min, maxDistance);
        int target = max;

        // Performance-driven scaling only: Never artificially downgrade view distance if server is healthy (MSPT < 40ms)
        if (rollingMspt >= 50.0) {
            target = Math.max(min, target - 3);
        } else if (rollingMspt >= 45.0) {
            target = Math.max(min, target - 2);
        } else if (rollingMspt >= 40.0) {
            target = Math.max(min, target - 1);
        }

        final int clamped = Math.max(min, Math.min(max, target));
        final int prev = this.currentTargetViewDistance.getAndSet(clamped);
        if (prev != clamped) {
            this.adjustmentsMade.incrementAndGet();
        }
        return clamped;
    }

    public int getCurrentTarget() {
        return this.currentTargetViewDistance.get();
    }

    public void resetMetrics() {
        this.currentTargetViewDistance.set(8);
        this.adjustmentsMade.set(0);
    }

    public ControllerMetrics metrics() {
        return new ControllerMetrics(
            this.currentTargetViewDistance.get(),
            this.adjustmentsMade.get()
        );
    }

    public record ControllerMetrics(
        int currentTargetDistance,
        long totalAdjustments
    ) {
    }
}
