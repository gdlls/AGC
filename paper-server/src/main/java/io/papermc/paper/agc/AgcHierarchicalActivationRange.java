package io.papermc.paper.agc;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Hierarchical Entity Activation Range (EAR 2.0).
 *
 * <p>Under high-density environments (500+ players across 50+ worlds), ticking every mob's
 * navigation, behavior trees, and physics every tick causes severe CPU load. This engine assigns
 * entities into a 4-tier activation hierarchy based on squared Euclidean distance to nearest player:</p>
 *
 * <ul>
 *   <li><b>TIER 0 (ACTIVE):</b> Within near radius (&lt; 16 blocks). Ticks every single tick (1:1).</li>
 *   <li><b>TIER 1 (REDUCED):</b> Medium distance (16 - 32 blocks). Ticks every 2nd tick (1:2).</li>
 *   <li><b>TIER 2 (SLOW):</b> Far distance (32 - 64 blocks). Ticks every 5th tick (1:5).</li>
 *   <li><b>TIER 3 (DORMANT):</b> Distant (&gt; 64 blocks). Navigation & AI frozen, only passive despawn checks (1:20).</li>
 * </ul>
 */
public final class AgcHierarchicalActivationRange {

    private static final AgcHierarchicalActivationRange INSTANCE = new AgcHierarchicalActivationRange();

    public enum ActivationTier {
        /** Ticks every 1 tick */
        ACTIVE(1),
        /** Ticks every 2 ticks */
        REDUCED(2),
        /** Ticks every 5 ticks */
        SLOW(5),
        /** Ticks every 20 ticks */
        DORMANT(20);

        private final int tickInterval;

        ActivationTier(final int tickInterval) {
            this.tickInterval = tickInterval;
        }

        public int tickInterval() {
            return this.tickInterval;
        }

        public boolean shouldTick(final long currentTick) {
            if (this.tickInterval <= 1) {
                return true;
            }
            return (currentTick % this.tickInterval) == 0;
        }
    }

    // Telemetry & tier counters
    private final AtomicLong activeTicked = new AtomicLong();
    private final AtomicLong reducedTicked = new AtomicLong();
    private final AtomicLong slowTicked = new AtomicLong();
    private final AtomicLong dormantSkipped = new AtomicLong();

    public static AgcHierarchicalActivationRange get() {
        return INSTANCE;
    }

    private AgcHierarchicalActivationRange() {}

    /**
     * Determines the activation tier for an entity based on squared distance to the nearest player.
     *
     * @param distanceSq Squared distance to the closest player in blocks^2
     * @param isHostile  Whether the entity is a hostile mob (which gets a wider active range)
     * @return Resolved {@link ActivationTier}
     */
    public ActivationTier calculateTier(final double distanceSq, final boolean isHostile) {
        if (distanceSq < 0.0) {
            return ActivationTier.ACTIVE;
        }

        final double nearLimitSq = isHostile ? 24.0 * 24.0 : 16.0 * 16.0;   // 576 or 256
        final double medLimitSq = isHostile ? 40.0 * 40.0 : 32.0 * 32.0;    // 1600 or 1024
        final double farLimitSq = isHostile ? 72.0 * 72.0 : 64.0 * 64.0;    // 5184 or 4096

        if (distanceSq <= nearLimitSq) {
            return ActivationTier.ACTIVE;
        }
        if (distanceSq <= medLimitSq) {
            return ActivationTier.REDUCED;
        }
        if (distanceSq <= farLimitSq) {
            return ActivationTier.SLOW;
        }
        return ActivationTier.DORMANT;
    }

    /**
     * Checks if an entity in a given tier should execute its tick on the specified server tick number.
     *
     * @param tier        Activation tier
     * @param currentTick Current world/server tick count
     * @return true if the entity should tick
     */
    public boolean shouldTickEntity(final ActivationTier tier, final long currentTick) {
        if (tier == null) {
            return true;
        }

        final boolean tick = tier.shouldTick(currentTick);
        if (tick) {
            switch (tier) {
                case ACTIVE -> this.activeTicked.incrementAndGet();
                case REDUCED -> this.reducedTicked.incrementAndGet();
                case SLOW -> this.slowTicked.incrementAndGet();
                case DORMANT -> {}
            }
        } else {
            this.dormantSkipped.incrementAndGet();
        }
        return tick;
    }

    /**
     * Resets tier telemetry counters.
     */
    public void resetMetrics() {
        this.activeTicked.set(0);
        this.reducedTicked.set(0);
        this.slowTicked.set(0);
        this.dormantSkipped.set(0);
    }

    public EarMetrics metrics() {
        return new EarMetrics(
            this.activeTicked.get(),
            this.reducedTicked.get(),
            this.slowTicked.get(),
            this.dormantSkipped.get()
        );
    }

    public record EarMetrics(
        long activeTicked,
        long reducedTicked,
        long slowTicked,
        long dormantSkipped
    ) {
        public long totalEvaluations() {
            return this.activeTicked + this.reducedTicked + this.slowTicked + this.dormantSkipped;
        }

        public double skipRatio() {
            final long total = totalEvaluations();
            if (total == 0) return 0.0;
            return (double) this.dormantSkipped / (double) total;
        }
    }
}
