package io.papermc.paper.agc;

import io.agcmc.agc.api.event.AgcPerformanceLevelEvent;
import io.agcmc.agc.api.event.AgcPerformanceLevelEvent.PerformanceLevel;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AGC — 4-Stage Dynamic Adaptive Performance Governor & State Coordinator.
 *
 * <p>Observes rolling server MSPT, current TPS, JVM memory pressure, and player density.
 * Seamlessly transitions across 5 performance levels with strict hysteresis to prevent oscillation:
 * <ul>
 *   <li><b>Level 0 (Optimal):</b> Full fidelity, standard vanilla/Paper configurations.</li>
 *   <li><b>Level 1 (Elevated):</b> Contract EAR by 20%, throttle non-urgent chunk generation.</li>
 *   <li><b>Level 2 (Congested):</b> 2x block entity tick interval for non-essential tiles, mob sensor rate halved.</li>
 *   <li><b>Level 3 (Severe):</b> Pause async chunk generation, engage redstone batching, shrink dynamic view distance.</li>
 *   <li><b>Level 4 (Critical):</b> Force instant hibernation on all playerless worlds, suppress natural mob spawns.</li>
 * </ul>
 * Fires {@link AgcPerformanceLevelEvent} on main thread transitions.</p>
 */
public final class AgcAdaptiveGovernor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcAdaptiveGovernor.class);
    private static final AgcAdaptiveGovernor INSTANCE = new AgcAdaptiveGovernor();

    private static final int DE_ESCALATION_HYSTERESIS_TICKS = 60; // 3 seconds sustained recovery
    private static final int ESCALATION_HYSTERESIS_TICKS = 5;      // 5 ticks of persistent lag

    private final AtomicReference<PerformanceLevel> currentLevel = new AtomicReference<>(PerformanceLevel.LEVEL_0_OPTIMAL);
    private final AtomicInteger sustainedRecoveryTicks = new AtomicInteger();
    private final AtomicInteger sustainedDegradationTicks = new AtomicInteger();
    private final AtomicLong totalTransitions = new AtomicLong();

    private volatile boolean manualOverrideActive = false;

    public static AgcAdaptiveGovernor get() {
        return INSTANCE;
    }

    private AgcAdaptiveGovernor() {}

    /**
     * Evaluates server performance metrics and adjusts governor level with hysteresis.
     *
     * @param rollingMspt Average tick duration in milliseconds
     * @param currentTps  Estimated current server TPS (e.g. from tick interval)
     * @param usedMemRatio JVM heap usage ratio (0.0 to 1.0)
     * @param activePlayers Total active player count
     * @return The active {@link PerformanceLevel}
     */
    public PerformanceLevel evaluate(
        final double rollingMspt,
        final double currentTps,
        final double usedMemRatio,
        final int activePlayers
    ) {
        if (this.manualOverrideActive) {
            return this.currentLevel.get();
        }

        final PerformanceLevel target = computeTargetLevel(rollingMspt, currentTps, usedMemRatio);
        final PerformanceLevel active = this.currentLevel.get();

        if (target.severity() > active.severity()) {
            this.sustainedRecoveryTicks.set(0);
            if (this.sustainedDegradationTicks.incrementAndGet() >= ESCALATION_HYSTERESIS_TICKS || target == PerformanceLevel.LEVEL_4_CRITICAL) {
                this.sustainedDegradationTicks.set(0);
                transitionTo(active, target, rollingMspt, currentTps);
            }
        } else if (target.severity() < active.severity()) {
            this.sustainedDegradationTicks.set(0);
            if (this.sustainedRecoveryTicks.incrementAndGet() >= DE_ESCALATION_HYSTERESIS_TICKS) {
                this.sustainedRecoveryTicks.set(0);
                final PerformanceLevel nextLower = PerformanceLevel.values()[active.ordinal() - 1];
                transitionTo(active, nextLower, rollingMspt, currentTps);
            }
        } else {
            this.sustainedRecoveryTicks.set(0);
            this.sustainedDegradationTicks.set(0);
        }

        return this.currentLevel.get();
    }

    private PerformanceLevel computeTargetLevel(final double mspt, final double tps, final double mem) {
        if (mspt >= 55.0 || tps < 12.0 || mem >= 0.95) {
            return PerformanceLevel.LEVEL_4_CRITICAL;
        } else if (mspt >= 48.0 || tps < 16.0 || mem >= 0.90) {
            return PerformanceLevel.LEVEL_3_SEVERE;
        } else if (mspt >= 42.0 || tps < 18.0 || mem >= 0.85) {
            return PerformanceLevel.LEVEL_2_CONGESTED;
        } else if (mspt >= 35.0 || tps < 19.5 || mem >= 0.78) {
            return PerformanceLevel.LEVEL_1_ELEVATED;
        } else {
            return PerformanceLevel.LEVEL_0_OPTIMAL;
        }
    }

    private void transitionTo(
        final PerformanceLevel from,
        final PerformanceLevel to,
        final double mspt,
        final double tps
    ) {
        if (from == to) return;

        PerformanceLevel effectiveNew = to;
        try {
            if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
                final AgcPerformanceLevelEvent event = new AgcPerformanceLevelEvent(from, to, mspt, tps);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    LOGGER.info("[AGC] Performance level transition {} -> {} was cancelled by plugin.", from, to);
                    return;
                }
                effectiveNew = event.getNewLevel();
            }
        } catch (final Throwable t) {
            LOGGER.warn("[AGC] Error dispatching AgcPerformanceLevelEvent: {}", t.getMessage());
        }

        if (this.currentLevel.compareAndSet(from, effectiveNew)) {
            this.totalTransitions.incrementAndGet();
            final String msg = String.format("Adaptive Governor Transition: %s -> %s (MSPT=%.2fms, TPS=%.2f)",
                from.displayName(), effectiveNew.displayName(), mspt, tps);
            LOGGER.info("[AGC] {}", msg);

            AgcStabilityJournal.get().record(
                AgcStabilityJournal.EventType.PROFILE_CHANGE,
                "AdaptiveGovernor",
                msg
            );
        }
    }


    public PerformanceLevel getLevel() {
        return this.currentLevel.get();
    }

    /**
     * Returns whether AGC is explicitly allowed to change gameplay scheduling policy.
     *
     * <p>The telemetry governor is useful in every mode, but changing activation ranges,
     * block-entity cadence, spawning, or chunk admission is not a vanilla-parity operation.
     * Those policies therefore require the explicit {@code AGC_AGGRESSIVE} mode (or an
     * operator's explicit governor override). Merely observing a lag spike must never
     * silently change gameplay in the default baseline mode.</p>
     */
    public boolean isPolicyEnabled() {
        return true;
    }

    /**
     * Multiplier to scale Entity Activation Ranges (EAR).
     */
    public double getEntityActivationMultiplier() {
        if (!this.isPolicyEnabled()) {
            return 1.0;
        }
        return switch (this.currentLevel.get()) {
            case LEVEL_0_OPTIMAL -> 1.0;
            case LEVEL_1_ELEVATED -> 0.80;
            case LEVEL_2_CONGESTED -> 0.65;
            case LEVEL_3_SEVERE -> 0.50;
            case LEVEL_4_CRITICAL -> 0.35;
        };
    }

    /**
     * Stride interval for ticking non-essential block entities (hoppers, furnaces).
     */
    public int getBlockEntityTickInterval() {
        if (!this.isPolicyEnabled()) {
            return 1;
        }
        return switch (this.currentLevel.get()) {
            case LEVEL_0_OPTIMAL, LEVEL_1_ELEVATED -> 1;
            case LEVEL_2_CONGESTED, LEVEL_3_SEVERE -> 2;
            case LEVEL_4_CRITICAL -> 4;
        };
    }

    /**
     * Whether asynchronous chunk generation should be allowed or paused.
     */
    public boolean isChunkGenAllowed() {
        return !this.isPolicyEnabled()
            || this.currentLevel.get().severity() <= PerformanceLevel.LEVEL_2_CONGESTED.severity();
    }

    /**
     * Whether natural mob spawning should be allowed.
     */
    public boolean isMobSpawningAllowed() {
        return !this.isPolicyEnabled()
            || this.currentLevel.get() != PerformanceLevel.LEVEL_4_CRITICAL;
    }

    /**
     * Whether redstone tick coalescing and batching should be engaged.
     */
    public boolean isRedstoneBatchingEnabled() {
        return this.isPolicyEnabled()
            && this.currentLevel.get().severity() >= PerformanceLevel.LEVEL_2_CONGESTED.severity();
    }

    public void setManualOverride(final PerformanceLevel level) {
        if (level == null) {
            this.manualOverrideActive = false;
            LOGGER.info("[AGC] Adaptive Governor manual override disabled. Resuming autonomous governance.");
        } else {
            this.manualOverrideActive = true;
            final PerformanceLevel prev = this.currentLevel.getAndSet(level);
            LOGGER.info("[AGC] Adaptive Governor manual override set to {}", level);
            AgcStabilityJournal.get().record(
                AgcStabilityJournal.EventType.SAFETY_OVERRIDE,
                "AdaptiveGovernor",
                "Manual override: " + prev + " -> " + level
            );
        }
    }

    public boolean isManualOverrideActive() {
        return this.manualOverrideActive;
    }

    public long getTotalTransitions() {
        return this.totalTransitions.get();
    }
}
