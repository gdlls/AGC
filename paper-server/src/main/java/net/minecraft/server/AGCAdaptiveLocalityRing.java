package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local adaptive locality ring for helper allocations.
 *
 * It manages only helper scratch budgets. It never owns live world, entity,
 * chunk or player state and is cleared logically at every tick boundary.
 */
public final class AGCAdaptiveLocalityRing {
    public static final AGCAdaptiveLocalityRing INSTANCE = new AGCAdaptiveLocalityRing();

    private static final int SLOTS = 64;
    private final long[] slotBytes = new long[SLOTS];
    private final AtomicLong claims = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();
    private volatile long tickSequence;
    private volatile long maxBytes = 128L * 1024L * 1024L;
    private volatile String lastReason = "cold";

    private AGCAdaptiveLocalityRing() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int slot = (int) (this.tickSequence & (SLOTS - 1));
        this.slotBytes[slot] = 0L;
        final long memory = Runtime.getRuntime().maxMemory();
        this.maxBytes = Math.max(16L * 1024L * 1024L, Math.min(512L * 1024L * 1024L, memory / 16L));
        this.lastReason = "ring slot reset slot=" + slot;
    }

    public Admission claim(final String owner, final long bytes) {
        this.claims.incrementAndGet();
        final int slot = (int) (this.tickSequence & (SLOTS - 1));
        final long safeBytes = Math.max(1L, bytes);
        final AGCScale19LogicKernel.Grant grant = AGCScale19LogicKernel.INSTANCE.claim(
            AGCScale19LogicKernel.Axis.LOCALITY_RING,
            Math.max(1L, safeBytes / 4096L),
            "adaptive-locality-ring " + safe(owner)
        );
        if (!grant.admitted() || this.slotBytes[slot] + safeBytes > this.maxBytes) {
            this.delayed.incrementAndGet();
            this.lastReason = grant.admitted() ? "locality ring byte budget preserved for " + safe(owner) : grant.reason();
            return new Admission(false, this.slotBytes[slot], this.maxBytes, this.lastReason);
        }
        this.slotBytes[slot] += safeBytes;
        this.admitted.incrementAndGet();
        this.lastReason = "admitted locality bytes=" + safeBytes + " owner=" + safe(owner);
        return new Admission(true, this.slotBytes[slot], this.maxBytes, this.lastReason);
    }

    public String statusLine() {
        final int slot = (int) (this.tickSequence & (SLOTS - 1));
        return "AGCAdaptiveLocalityRing{tick=" + this.tickSequence
            + ", slot=" + slot
            + ", slotBytes=" + this.slotBytes[slot]
            + ", maxBytes=" + this.maxBytes
            + ", claims=" + this.claims.get()
            + ", admitted=" + this.admitted.get()
            + ", delayed=" + this.delayed.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String owner) {
        return owner == null || owner.isBlank() ? "helper" : owner;
    }

    public record Admission(boolean admitted, long usedBytes, long maxBytes, String reason) {}
}
