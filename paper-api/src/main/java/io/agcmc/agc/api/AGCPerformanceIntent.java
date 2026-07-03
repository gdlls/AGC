package io.agcmc.agc.api;

import org.jetbrains.annotations.NotNull;

/**
 * Plugin-facing description of work that should follow AGC no-invasion lanes.
 * This is advisory and never grants permission to mutate Bukkit state off the
 * primary thread.
 */
public final class AGCPerformanceIntent {
    private final AGCPerformanceGoal goal;
    private final int estimatedCost;
    private final boolean visibleCommit;
    private final boolean readOnlyPrepare;
    private final String reason;

    private AGCPerformanceIntent(final Builder builder) {
        this.goal = builder.goal;
        this.estimatedCost = Math.max(1, builder.estimatedCost);
        this.visibleCommit = builder.visibleCommit;
        this.readOnlyPrepare = builder.readOnlyPrepare;
        this.reason = builder.reason;
    }

    public static @NotNull Builder builder(final @NotNull AGCPerformanceGoal goal) {
        return new Builder(goal);
    }

    public @NotNull AGCPerformanceGoal goal() { return this.goal; }
    public int estimatedCost() { return this.estimatedCost; }
    public boolean visibleCommit() { return this.visibleCommit; }
    public boolean readOnlyPrepare() { return this.readOnlyPrepare; }
    public @NotNull String reason() { return this.reason; }

    public static final class Builder {
        private final AGCPerformanceGoal goal;
        private int estimatedCost = 1;
        private boolean visibleCommit;
        private boolean readOnlyPrepare = true;
        private String reason = "unspecified";

        private Builder(final AGCPerformanceGoal goal) {
            this.goal = java.util.Objects.requireNonNull(goal, "goal");
        }

        public @NotNull Builder estimatedCost(final int estimatedCost) { this.estimatedCost = estimatedCost; return this; }
        public @NotNull Builder visibleCommit(final boolean visibleCommit) { this.visibleCommit = visibleCommit; return this; }
        public @NotNull Builder readOnlyPrepare(final boolean readOnlyPrepare) { this.readOnlyPrepare = readOnlyPrepare; return this; }
        public @NotNull Builder reason(final @NotNull String reason) { this.reason = reason; return this; }
        public @NotNull AGCPerformanceIntent build() { return new AGCPerformanceIntent(this); }
    }
}
