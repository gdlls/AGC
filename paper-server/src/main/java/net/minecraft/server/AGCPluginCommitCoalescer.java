package net.minecraft.server;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha23 plugin commit coalescer.
 *
 * This coalesces only classification/ledger tickets. It does not merge Bukkit
 * events, does not reorder plugin callbacks and never moves Bukkit-visible
 * mutation off the primary ordered commit lane.
 */
public final class AGCPluginCommitCoalescer {
    public static final AGCPluginCommitCoalescer INSTANCE = new AGCPluginCommitCoalescer();

    private final AtomicLong classified = new AtomicLong();
    private final AtomicLong readOnly = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private final AtomicLong forbidden = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCPluginCommitCoalescer() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "reset";
    }

    public Entry classify(final String pluginName, final String operation, final boolean declaredReadOnly, final long cost) {
        final String safePlugin = pluginName == null || pluginName.isBlank() ? "unknown" : pluginName;
        final String op = operation == null ? "unknown" : operation;
        final String lower = op.toLowerCase(Locale.ROOT);
        this.classified.incrementAndGet();
        if (lower.contains("asyncmutate") || lower.contains("offthread") || lower.contains("forbidden")) {
            this.forbidden.incrementAndGet();
            this.lastReason = "forbidden off-thread Bukkit mutation plugin=" + safePlugin + " op=" + op;
            return new Entry(false, false, true, 0L, this.lastReason);
        }
        if (visibleMutation(lower)) {
            this.ordered.incrementAndGet();
            final long tickets = Math.max(1L, Math.min(4096L, cost));
            this.lastReason = "Bukkit-visible plugin work remains ordered plugin=" + safePlugin + " op=" + op;
            return new Entry(false, true, false, tickets, this.lastReason);
        }
        if (declaredReadOnly || lower.contains("readonly") || lower.contains("pure") || lower.contains("plan") || lower.contains("classify")) {
            this.readOnly.incrementAndGet();
            final long tickets = Math.max(1L, Math.min(16_384L, Math.max(1L, cost / 2L)));
            this.lastReason = "coalesced read-only plugin helper tickets=" + tickets + " plugin=" + safePlugin;
            return new Entry(true, false, false, tickets, this.lastReason);
        }
        this.ordered.incrementAndGet();
        this.lastReason = "unknown plugin work kept ordered plugin=" + safePlugin + " op=" + op;
        return new Entry(false, true, false, Math.max(1L, Math.min(4096L, cost)), this.lastReason);
    }

    public String statusLine() {
        return "AGCPluginCommitCoalescer{tick=" + this.tickSequence
            + ", classified=" + this.classified.get()
            + ", readOnly=" + this.readOnly.get()
            + ", ordered=" + this.ordered.get()
            + ", forbidden=" + this.forbidden.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static boolean visibleMutation(final String lower) {
        return lower.contains("setblock")
            || lower.contains("teleport")
            || lower.contains("spawn")
            || lower.contains("inventory")
            || lower.contains("scoreboard")
            || lower.contains("command")
            || lower.contains("event")
            || lower.contains("world")
            || lower.contains("blockbreak")
            || lower.contains("blockplace")
            || lower.contains("damage")
            || lower.contains("interaction");
    }

    public record Entry(boolean readOnlyPrepare, boolean orderedCommit, boolean forbiddenMutation, long coalescedTickets, String reason) {}
}
