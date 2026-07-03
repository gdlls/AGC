package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU/memory placement planner for invisible AGC work.
 *
 * It reserves space for primary-thread gameplay, plugins, Netty, GC and OS, then
 * places only read-only helper tasks into spare capacity windows.
 */
public final class AGCResourcePlacementPlanner {
    public static final AGCResourcePlacementPlanner INSTANCE = new AGCResourcePlacementPlanner();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong placed = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastWorkers;
    private volatile int lastReservePercent;
    private volatile String lastReason = "cold";

    private AGCResourcePlacementPlanner() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.lastReservePercent = processors >= 16 ? 34 : 28;
        this.lastWorkers = Math.max(1, processors - Math.max(2, (processors * this.lastReservePercent + 99) / 100));
        this.lastReason = "reserved primary/plugins/netty/gc/os workers=" + this.lastWorkers;
    }

    public Placement place(final String taskName, final long cost, final boolean visibleCommit) {
        this.requested.incrementAndGet();
        if (visibleCommit) {
            this.delayed.incrementAndGet();
            this.lastReason = "visible commit is not placed in helper pool: " + safe(taskName);
            return new Placement(false, true, this.lastWorkers, this.lastReservePercent, this.lastReason);
        }
        final AGCScale20LosslessPipeline.Grant grant = AGCScale20LosslessPipeline.INSTANCE.claim(
            AGCScale20LosslessPipeline.Stage.RESOURCE_PLACEMENT,
            Math.max(1L, cost),
            "resource-placement " + safe(taskName)
        );
        if (!grant.admitted()) {
            this.delayed.incrementAndGet();
            this.lastReason = grant.reason();
            return new Placement(false, false, this.lastWorkers, this.lastReservePercent, this.lastReason);
        }
        this.placed.incrementAndGet();
        this.lastReason = "placed read-only helper task=" + safe(taskName) + " workers=" + this.lastWorkers;
        return new Placement(true, false, this.lastWorkers, this.lastReservePercent, this.lastReason);
    }

    public String statusLine() {
        return "AGCResourcePlacementPlanner{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", placed=" + this.placed.get()
            + ", delayed=" + this.delayed.get()
            + ", lastWorkers=" + this.lastWorkers
            + ", lastReservePercent=" + this.lastReservePercent
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String value) {
        return value == null || value.isBlank() ? "task" : value;
    }

    public record Placement(boolean placed, boolean visibleCommit, int helperWorkers, int reservePercent, String reason) {}
}
