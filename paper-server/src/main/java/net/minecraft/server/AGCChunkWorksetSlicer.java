package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Slices chunk intent work into stable, FIFO-compatible worksets.
 *
 * This never starts, cancels or reorders chunk work. It only prepares stable
 * workset metadata so the real Paper chunk queues can be fed fairly without
 * skipping the FIFO head or changing completion order.
 */
public final class AGCChunkWorksetSlicer {
    public static final AGCChunkWorksetSlicer INSTANCE = new AGCChunkWorksetSlicer();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong sliced = new AtomicLong();
    private final AtomicLong fifoWait = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastSlices;
    private volatile long lastWorksetKey;
    private volatile String lastReason = "cold";

    private AGCChunkWorksetSlicer() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "tick reset";
    }

    public Workset slice(final UUID playerId, final AGCChunkBudget.Operation operation, final int radius, final long queueDepth, final String reason) {
        this.requested.incrementAndGet();
        final int safeRadius = Math.max(1, radius);
        final int slices = Math.max(1, Math.min(256, (safeRadius * safeRadius + 31) / 32));
        final long key = stableKey(playerId, operation, safeRadius, queueDepth);
        final AGCScale20LosslessPipeline.Grant grant = AGCScale20LosslessPipeline.INSTANCE.claim(
            AGCScale20LosslessPipeline.Stage.CHUNK_PIPELINE,
            Math.max(1L, slices + Math.max(0L, queueDepth / 16L)),
            "chunk-workset " + safe(reason)
        );
        this.lastSlices = slices;
        this.lastWorksetKey = key;
        if (!grant.admitted()) {
            this.fifoWait.incrementAndGet();
            this.lastReason = grant.reason();
            return new Workset(false, true, slices, key, this.lastReason);
        }
        this.sliced.incrementAndGet();
        this.lastReason = "sliced FIFO-compatible workset slices=" + slices + " key=" + Long.toUnsignedString(key);
        return new Workset(true, false, slices, key, this.lastReason);
    }

    public String statusLine() {
        return "AGCChunkWorksetSlicer{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", sliced=" + this.sliced.get()
            + ", fifoWait=" + this.fifoWait.get()
            + ", lastSlices=" + this.lastSlices
            + ", lastWorksetKey=" + Long.toUnsignedString(this.lastWorksetKey)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final UUID playerId, final AGCChunkBudget.Operation operation, final int radius, final long queueDepth) {
        long hash = 0x510e527fade682d1L;
        hash = mix(hash, playerId == null ? 0L : playerId.getMostSignificantBits());
        hash = mix(hash, playerId == null ? 0L : playerId.getLeastSignificantBits());
        hash = mix(hash, operation == null ? 0L : operation.ordinal());
        hash = mix(hash, radius);
        hash = mix(hash, queueDepth);
        return hash;
    }

    private static long mix(final long hash, final long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "chunk" : reason;
    }

    public record Workset(boolean admitted, boolean fifoWait, int slices, long worksetKey, String reason) {}
}
