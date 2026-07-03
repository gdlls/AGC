package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Plans read-only helper work distribution across spare CPU. This is a planner,
 * not a scheduler: Bukkit-visible tasks still commit on the primary thread.
 */
public final class AGCReadOnlyWorkStealingPlanner {
    public static final AGCReadOnlyWorkStealingPlanner INSTANCE = new AGCReadOnlyWorkStealingPlanner();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastWorkers;
    private volatile long lastBudget;
    private volatile String lastReason = "cold";

    private AGCReadOnlyWorkStealingPlanner() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.lastWorkers = Math.max(1, processors - Math.max(2, (processors * 25 + 99) / 100));
        this.lastBudget = Math.max(8192L, this.lastWorkers * 131_072L);
        this.lastReason = "workers=" + this.lastWorkers + " budget=" + this.lastBudget;
    }

    public Admission claim(final long cost, final String reason) {
        this.requested.incrementAndGet();
        final long safeCost = Math.max(1L, cost);
        if (safeCost > this.lastBudget) {
            this.ordered.incrementAndGet();
            this.lastReason = "read-only worker budget exhausted: " + safe(reason);
            return new Admission(false, this.lastWorkers, this.lastBudget, this.lastReason);
        }
        this.lastBudget -= safeCost;
        this.admitted.incrementAndGet();
        this.lastReason = "read-only helper admitted: " + safe(reason);
        return new Admission(true, this.lastWorkers, this.lastBudget, this.lastReason);
    }

    public String statusLine() {
        return "AGCReadOnlyWorkStealingPlanner{tick=" + this.tickSequence
            + ", workers=" + this.lastWorkers
            + ", budget=" + this.lastBudget
            + ", requested=" + this.requested.get()
            + ", admitted=" + this.admitted.get()
            + ", ordered=" + this.ordered.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String reason) { return reason == null || reason.isBlank() ? "unspecified" : reason; }
    public record Admission(boolean admitted, int workers, long remainingBudget, String reason) {}
}
