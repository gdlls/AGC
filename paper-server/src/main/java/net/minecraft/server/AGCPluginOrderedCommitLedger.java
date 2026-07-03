package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** Ordered commit ledger for plugin-visible work; never executes mutations off-thread. */
public final class AGCPluginOrderedCommitLedger {
    public static final AGCPluginOrderedCommitLedger INSTANCE = new AGCPluginOrderedCommitLedger();

    private final AtomicLong readOnly = new AtomicLong();
    private final AtomicLong orderedCommit = new AtomicLong();
    private final AtomicLong forbidden = new AtomicLong();
    private volatile long tickSequence;
    private volatile long ticketSequence;
    private volatile String lastReason = "cold";

    private AGCPluginOrderedCommitLedger() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "reset ticketSequence=" + this.ticketSequence;
    }

    public Entry classify(final String owner, final String operation, final boolean readOnlyHint, final long cost) {
        final String op = operation == null ? "unknown" : operation.toLowerCase(java.util.Locale.ROOT);
        final boolean visible = op.contains("setblock") || op.contains("teleport") || op.contains("spawn")
            || op.contains("inventory") || op.contains("scoreboard") || op.contains("event")
            || op.contains("command") || op.contains("blockbreak") || op.contains("blockplace") || op.contains("world");
        final boolean forbiddenMutation = !readOnlyHint && op.contains("off-thread");
        final long ticket = ++this.ticketSequence;
        if (forbiddenMutation) {
            this.forbidden.incrementAndGet();
            this.lastReason = "forbid off-thread mutation owner=" + safe(owner) + " op=" + operation;
            return new Entry(ticket, false, true, true, Math.max(1L, cost), this.lastReason);
        }
        if (visible || !readOnlyHint) {
            this.orderedCommit.incrementAndGet();
            this.lastReason = "ordered plugin commit ticket=" + ticket + " owner=" + safe(owner) + " op=" + operation;
            return new Entry(ticket, false, true, false, Math.max(1L, cost), this.lastReason);
        }
        this.readOnly.incrementAndGet();
        this.lastReason = "read-only plugin helper ticket=" + ticket + " owner=" + safe(owner) + " op=" + operation;
        return new Entry(ticket, true, false, false, Math.max(1L, cost), this.lastReason);
    }

    public String statusLine() {
        return "AGCPluginOrderedCommitLedger{tick=" + this.tickSequence
            + ", tickets=" + this.ticketSequence
            + ", readOnly=" + this.readOnly.get()
            + ", orderedCommit=" + this.orderedCommit.get()
            + ", forbidden=" + this.forbidden.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String value) { return value == null || value.isBlank() ? "unknown" : value; }

    public record Entry(long ticket, boolean readOnlyPrepare, boolean orderedCommit, boolean forbiddenMutation, long cost, String reason) {}
}
