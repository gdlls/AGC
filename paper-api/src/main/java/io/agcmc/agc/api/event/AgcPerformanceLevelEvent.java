package io.agcmc.agc.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when the AGC Adaptive Performance Governor transitions to a new performance level.
 *
 * <p>Plugins can listen to this event to adapt their own background tasks or override the target level.</p>
 */
public class AgcPerformanceLevelEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    public enum PerformanceLevel {
        /** TPS 20.0, MSPT &lt; 30ms: Optimal fidelity and full standard parameters. */
        LEVEL_0_OPTIMAL(0, "Optimal"),
        /** TPS &lt; 19.5, MSPT &gt; 35ms: EAR 20% contraction, slight chunk gen throttling. */
        LEVEL_1_ELEVATED(1, "Elevated Load"),
        /** TPS &lt; 18.0, MSPT &gt; 42ms: Block entity tick throttling (2x interval), mob sensor throttling. */
        LEVEL_2_CONGESTED(2, "Congested"),
        /** TPS &lt; 16.0, MSPT &gt; 48ms: World gen paused, redstone batching, dynamic view distance reduced. */
        LEVEL_3_SEVERE(3, "Severe Congestion"),
        /** TPS &lt; 12.0, MSPT &gt; 55ms: Inactive worlds forced hibernation, natural mob spawns suppressed. */
        LEVEL_4_CRITICAL(4, "Critical Emergency");

        private final int severity;
        private final String displayName;

        PerformanceLevel(final int severity, final String displayName) {
            this.severity = severity;
            this.displayName = displayName;
        }

        public int severity() {
            return this.severity;
        }

        public String displayName() {
            return this.displayName;
        }
    }

    private final PerformanceLevel previousLevel;
    private PerformanceLevel newLevel;
    private final double rollingMspt;
    private final double currentTps;
    private boolean cancelled = false;

    public AgcPerformanceLevelEvent(
        final @NotNull PerformanceLevel previousLevel,
        final @NotNull PerformanceLevel newLevel,
        final double rollingMspt,
        final double currentTps
    ) {
        super(false);
        this.previousLevel = previousLevel;
        this.newLevel = newLevel;
        this.rollingMspt = rollingMspt;
        this.currentTps = currentTps;
    }

    public @NotNull PerformanceLevel getPreviousLevel() {
        return this.previousLevel;
    }

    public @NotNull PerformanceLevel getNewLevel() {
        return this.newLevel;
    }

    public void setNewLevel(final @NotNull PerformanceLevel newLevel) {
        this.newLevel = newLevel;
    }

    public double getRollingMspt() {
        return this.rollingMspt;
    }

    public double getCurrentTps() {
        return this.currentTps;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(final boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
