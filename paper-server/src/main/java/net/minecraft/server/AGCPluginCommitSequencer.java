package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** Deterministic plugin commit sequencer: read-only helpers may be planned, visible operations stay ordered. */
public final class AGCPluginCommitSequencer {
    public static final AGCPluginCommitSequencer INSTANCE = new AGCPluginCommitSequencer();

    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong orderedCommits = new AtomicLong();
    private final AtomicLong readOnlyPlans = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCPluginCommitSequencer() {
    }

    public void beginTick(final long tick) {
        this.tickSequence = Math.max(0L, tick);
        this.lastReason = "reset";
    }

    public Decision classify(final String pluginName, final String operation, final boolean readOnly, final boolean visibleMutation, final long estimatedCost) {
        final long ticket = this.sequence.incrementAndGet();
        final boolean ordered = visibleMutation || !readOnly || isVisibleKeyword(operation);
        if (ordered) {
            this.orderedCommits.incrementAndGet();
        } else {
            this.readOnlyPlans.incrementAndGet();
        }
        this.lastReason = (ordered ? "ordered" : "read-only") + " plugin=" + safe(pluginName) + " operation=" + safe(operation) + " ticket=" + ticket + " cost=" + Math.max(1L, estimatedCost);
        return new Decision(!ordered, ordered, ticket, this.lastReason);
    }

    public String statusLine() {
        return "AGCPluginCommitSequencer{tick=" + this.tickSequence
            + ", nextTicket=" + this.sequence.get()
            + ", orderedCommits=" + this.orderedCommits.get()
            + ", readOnlyPlans=" + this.readOnlyPlans.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static boolean isVisibleKeyword(final String operation) {
        if (operation == null) {
            return false;
        }
        final String lower = operation.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("setblock") || lower.contains("teleport") || lower.contains("spawn")
            || lower.contains("inventory") || lower.contains("scoreboard") || lower.contains("event")
            || lower.contains("command") || lower.contains("blockbreak") || lower.contains("blockplace");
    }

    private static String safe(final String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public record Decision(boolean readOnlyPlan, boolean orderedCommit, long ticket, String reason) {}
}
