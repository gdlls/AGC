package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — EAR 4.0 (Hierarchical Entity Activation Range V4) 5-Tier LOD Governor.
 *
 * <p>Implements a 5-tier Level of Detail (LOD) entity activation system for 500+ active worlds.
 * Distributes compute budgets dynamically based on player proximity, combat engagement, and boss status:
 * <ul>
 *   <li><b>TIER 0 (Full Combat & Proximity, $\le 16\text{m}$):</b> 20Hz full AI, targeting, physics, pathfinding.</li>
 *   <li><b>TIER 1 (Standard Tick, $16\text{m} \sim 32\text{m}$):</b> 20Hz physics/movement, AI sensor cadence 2x.</li>
 *   <li><b>TIER 2 (Reduced Tick, $32\text{m} \sim 64\text{m}$):</b> Ticked every 2 ticks (10Hz) with motion interpolation.</li>
 *   <li><b>TIER 3 (Minimal Tick, $64\text{m} \sim 128\text{m}$):</b> Ticked every 4 ticks (5Hz), health/despawn timer only.</li>
 *   <li><b>TIER 4 (Frozen / Inactive, $> 128\text{m}$):</b> Completely frozen, inactive tick called once every 20 ticks (1Hz).</li>
 * </ul>
 * </p>
 */
public final class AgcHierarchicalActivationRangeV3 {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcHierarchicalActivationRangeV3.class);
    private static final AgcHierarchicalActivationRangeV3 INSTANCE = new AgcHierarchicalActivationRangeV3();

    public enum EarTier {
        /** Tier 0: <= 16m or actively in combat. Full 20Hz execution. */
        TIER_0_COMBAT_FULL,
        /** Tier 1: 16m ~ 32m. Full physics, standard AI. */
        TIER_1_STANDARD,
        /** Tier 2: 32m ~ 64m. Reduced cadence (10Hz / every 2 ticks). */
        TIER_2_REDUCED,
        /** Tier 3: 64m ~ 128m. Minimal cadence (5Hz / every 4 ticks). */
        TIER_3_MINIMAL,
        /** Tier 4: > 128m. Inactive/Frozen (1Hz / every 20 ticks). */
        TIER_4_FROZEN,

        // Legacy aliases for backward compatibility
        TIER_1_FULL_BRAIN,
        TIER_2_PHYSICS_ONLY,
        TIER_3_FROZEN_STATE,
        TIER_4_VIRTUAL_OFFHEAP
    }

    // Lossless parity: 48m and 64m thresholds guarantee full 20Hz execution across entire player visual
    // and mob farm engagement radius (preventing mobs from freezing or breaking pathfinding in farms)
    public static final double TIER_0_MAX_DIST_SQ = 48.0 * 48.0;   // 2304.0 (Full 20Hz combat & visual radius)
    public static final double TIER_1_MAX_DIST_SQ = 64.0 * 64.0;   // 4096.0 (Full 20Hz physics & navigation)
    public static final double TIER_2_MAX_DIST_SQ = 96.0 * 96.0;   // 9216.0
    public static final double TIER_3_MAX_DIST_SQ = 128.0 * 128.0; // 16384.0

    private final AtomicLong tier0Count = new AtomicLong();
    private final AtomicLong tier1Count = new AtomicLong();
    private final AtomicLong tier2Count = new AtomicLong();
    private final AtomicLong tier3Count = new AtomicLong();
    private final AtomicLong tier4Count = new AtomicLong();
    private final AtomicLong brainTicksSaved = new AtomicLong();

    public static AgcHierarchicalActivationRangeV3 get() {
        return INSTANCE;
    }

    private AgcHierarchicalActivationRangeV3() {}

    /**
     * Evaluates the EAR 4.0 tier for an entity.
     *
     * @param nearestPlayerDistSq Squared distance to closest active player
     * @param inCombatWithPlayer  true if entity is actively attacking/targeted by a player
     * @param isBossOrCustom      true if entity has custom immunity (e.g. EnderDragon, Wither)
     * @return Resolved {@link EarTier}
     */
    public EarTier evaluateTier(
        final double nearestPlayerDistSq,
        final boolean inCombatWithPlayer,
        final boolean isBossOrCustom
    ) {
        if (inCombatWithPlayer || isBossOrCustom || nearestPlayerDistSq <= TIER_0_MAX_DIST_SQ) {
            this.tier0Count.incrementAndGet();
            return EarTier.TIER_0_COMBAT_FULL;
        } else if (nearestPlayerDistSq <= TIER_1_MAX_DIST_SQ) {
            this.tier1Count.incrementAndGet();
            return EarTier.TIER_1_STANDARD;
        } else if (nearestPlayerDistSq <= TIER_2_MAX_DIST_SQ) {
            this.tier2Count.incrementAndGet();
            return EarTier.TIER_2_REDUCED;
        } else if (nearestPlayerDistSq <= TIER_3_MAX_DIST_SQ) {
            this.tier3Count.incrementAndGet();
            return EarTier.TIER_3_MINIMAL;
        } else {
            this.tier4Count.incrementAndGet();
            return EarTier.TIER_4_FROZEN;
        }
    }

    /**
     * Determines whether the entity should execute on the current tick based on its tier.
     */
    public boolean shouldTick(final EarTier tier, final long currentTick) {
        switch (tier) {
            case TIER_0_COMBAT_FULL:
            case TIER_1_FULL_BRAIN:
            case TIER_1_STANDARD:
                return true;
            case TIER_2_PHYSICS_ONLY:
            case TIER_2_REDUCED:
                // 10Hz (every 2 ticks)
                if ((currentTick & 1) == 0) {
                    return true;
                }
                this.brainTicksSaved.incrementAndGet();
                return false;
            case TIER_3_FROZEN_STATE:
            case TIER_3_MINIMAL:
                // 5Hz (every 4 ticks)
                if ((currentTick & 3) == 0) {
                    return true;
                }
                this.brainTicksSaved.incrementAndGet();
                return false;
            case TIER_4_VIRTUAL_OFFHEAP:
            case TIER_4_FROZEN:
            default:
                // 1Hz (every 20 ticks)
                if ((currentTick % 20) == 0) {
                    return true;
                }
                this.brainTicksSaved.incrementAndGet();
                return false;
        }
    }

    /**
     * Backward-compatible alias for shouldTick.
     */
    public boolean shouldTickBrain(final EarTier tier, final long currentTick) {
        return shouldTick(tier, currentTick);
    }

    public void resetMetrics() {
        this.tier0Count.set(0);
        this.tier1Count.set(0);
        this.tier2Count.set(0);
        this.tier3Count.set(0);
        this.tier4Count.set(0);
        this.brainTicksSaved.set(0);
    }

    public void clearMetrics() {
        resetMetrics();
    }

    public EarMetrics metrics() {
        return new EarMetrics(
            this.tier0Count.get(),
            this.tier1Count.get(),
            this.tier2Count.get(),
            this.tier3Count.get(),
            this.tier4Count.get(),
            this.brainTicksSaved.get()
        );
    }

    public record EarMetrics(
        long tier0Count,
        long tier1Count,
        long tier2Count,
        long tier3Count,
        long tier4Count,
        long brainTicksSaved
    ) {
        public long totalEvaluations() {
            return this.tier0Count + this.tier1Count + this.tier2Count + this.tier3Count + this.tier4Count;
        }
    }
}
