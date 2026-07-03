package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** CPU/memory-aware read-only worker router for alpha23. */
public final class AGCWorkerRoutePlanner {
    public static final AGCWorkerRoutePlanner INSTANCE = new AGCWorkerRoutePlanner();

    private final AtomicLong routed = new AtomicLong();
    private final AtomicLong keptOrdered = new AtomicLong();
    private volatile long tickSequence;
    private volatile long baseUnits;
    private volatile long remainingUnits;
    private volatile int helperWorkers;
    private volatile String lastReason = "cold";

    private AGCWorkerRoutePlanner() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reserved = processors >= 16 ? 4 : processors >= 8 ? 3 : 2;
        this.helperWorkers = Math.max(1, processors - reserved);
        final long maxMemory = Math.max(1L, Runtime.getRuntime().maxMemory());
        final long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final double headroom = Math.max(0.15D, 1.0D - ((double) used / (double) maxMemory));
        this.baseUnits = Math.max(131_072L, (long) (this.helperWorkers * 192_000L * headroom));
        this.remainingUnits = this.baseUnits;
        this.lastReason = "reset workers=" + this.helperWorkers + " headroom=" + Math.round(headroom * 100.0D) / 100.0D;
    }

    public Route route(final String job, final long cost, final boolean visible) {
        final long safeCost = Math.max(1L, cost);
        if (visible) {
            this.keptOrdered.incrementAndGet();
            this.lastReason = "visible job stays ordered: " + safe(job);
            return new Route(false, true, this.helperWorkers, this.remainingUnits, this.lastReason);
        }
        if (safeCost > this.remainingUnits) {
            this.keptOrdered.incrementAndGet();
            this.lastReason = "not enough spare read-only worker budget; helper waits: " + safe(job);
            return new Route(false, false, this.helperWorkers, this.remainingUnits, this.lastReason);
        }
        this.remainingUnits -= safeCost;
        this.routed.incrementAndGet();
        this.lastReason = "routed read-only helper job=" + safe(job) + " cost=" + safeCost;
        return new Route(true, false, this.helperWorkers, this.remainingUnits, this.lastReason);
    }

    public String statusLine() {
        return "AGCWorkerRoutePlanner{tick=" + this.tickSequence
            + ", helperWorkers=" + this.helperWorkers
            + ", baseUnits=" + this.baseUnits
            + ", remainingUnits=" + this.remainingUnits
            + ", routed=" + this.routed.get()
            + ", keptOrdered=" + this.keptOrdered.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String value) {
        return value == null || value.isBlank() ? "unspecified" : value;
    }

    public record Route(boolean routed, boolean orderedVisible, int helperWorkers, long remainingUnits, String reason) {}
}
