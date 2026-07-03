package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** Read-only multiverse lane allocator for conflict-free prepare waves. */
public final class AGCMultiverseLaneAllocator {
    public static final AGCMultiverseLaneAllocator INSTANCE = new AGCMultiverseLaneAllocator();

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCMultiverseLaneAllocator() {
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "reset";
    }

    public Allocation allocate(final int worlds, final int parallelGroups, final long translatorPending) {
        this.planned.incrementAndGet();
        final int safeWorlds = Math.max(1, worlds);
        final int safeGroups = Math.max(0, parallelGroups);
        final boolean prepare = translatorPending < 8192L && safeGroups > 0;
        if (!prepare) {
            this.ordered.incrementAndGet();
        }
        final int lanes = prepare ? Math.max(1, Math.min(safeGroups, Math.max(1, safeWorlds / 2))) : 0;
        this.lastReason = (prepare ? "prepare" : "ordered") + " worlds=" + safeWorlds + " groups=" + safeGroups + " lanes=" + lanes + " translatorPending=" + translatorPending;
        return new Allocation(prepare, lanes, this.lastReason);
    }

    public String statusLine() {
        return "AGCMultiverseLaneAllocator{tick=" + this.tickSequence
            + ", planned=" + this.planned.get()
            + ", ordered=" + this.ordered.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    public record Allocation(boolean readOnlyPrepare, int lanes, String reason) {}
}
