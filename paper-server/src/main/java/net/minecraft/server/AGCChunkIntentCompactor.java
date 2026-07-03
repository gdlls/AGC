package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk intent compactor for large survival servers. It coalesces only the
 * read-only planning shape of nearby chunk intents. Paper's actual chunk queue,
 * per-player FIFO head, completion order and chunk status ownership are not
 * changed.
 */
public final class AGCChunkIntentCompactor {
    public static final AGCChunkIntentCompactor INSTANCE = new AGCChunkIntentCompactor();

    private final ConcurrentHashMap<Long, AtomicLong> intentShapes = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong compacted = new AtomicLong();
    private final AtomicLong fifoWaits = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int regionShift = 5;
    private volatile int maxShapes = 262_144;
    private volatile long tickSequence;

    private AGCChunkIntentCompactor() {
    }

    public void configure(final boolean enabled, final int regionSizeChunks, final int maxShapes) {
        this.enabled = enabled;
        this.regionShift = shiftFor(Math.max(1, regionSizeChunks));
        this.maxShapes = Math.max(1, maxShapes);
    }

    public void beginTick(final long sequence) {
        if (sequence != this.tickSequence) {
            this.tickSequence = sequence;
            this.intentShapes.clear();
        }
    }

    public Admission plan(final UUID playerId, final AGCChunkBudget.Operation operation, final String worldKey, final int chunkX, final int chunkZ) {
        this.requests.incrementAndGet();
        if (!this.enabled) {
            this.fifoWaits.incrementAndGet();
            return new Admission(false, false, 0L, "chunk intent compactor disabled");
        }
        if (this.intentShapes.size() >= this.maxShapes) {
            this.fifoWaits.incrementAndGet();
            return new Admission(false, false, 0L, "chunk intent shape table full; preserve FIFO planning");
        }
        final long key = key(operation, worldKey, chunkX >> this.regionShift, chunkZ >> this.regionShift);
        final AtomicLong hits = this.intentShapes.computeIfAbsent(key, ignored -> new AtomicLong());
        final long count = hits.incrementAndGet();
        final long cost = Math.max(1L, (operation == AGCChunkBudget.Operation.GENERATE ? 4L : 2L) + count / 64L);
        final AGCScaleControlPlane.Admission control = AGCScaleControlPlane.INSTANCE.claim(AGCScaleControlPlane.Lane.CHUNK_INTENT_COMPACTION, cost, operation == null ? "chunk" : operation.name());
        if (!control.admitted()) {
            this.fifoWaits.incrementAndGet();
            return new Admission(false, count > 1L, count, control.reason());
        }
        this.compacted.incrementAndGet();
        return new Admission(true, count > 1L, count, "chunk intent shape compacted; per-player FIFO and completion order unchanged");
    }

    public String statusLine() {
        return "AGCChunkIntentCompactor{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", shapes=" + this.intentShapes.size()
            + ", requests=" + this.requests.get()
            + ", compacted=" + this.compacted.get()
            + ", fifoWaits=" + this.fifoWaits.get()
            + '}';
    }

    private static int shiftFor(final int regionSizeChunks) {
        int shift = 0;
        int size = 1;
        while (size < regionSizeChunks && shift < 12) {
            size <<= 1;
            ++shift;
        }
        return shift;
    }

    private static long key(final AGCChunkBudget.Operation operation, final String worldKey, final int regionX, final int regionZ) {
        long result = 1469598103934665603L;
        result = 1099511628211L * (result ^ (operation == null ? 0 : operation.ordinal()));
        result = 1099511628211L * (result ^ (worldKey == null ? 0 : worldKey.hashCode()));
        result = 1099511628211L * (result ^ regionX);
        result = 1099511628211L * (result ^ regionZ);
        return result;
    }

    public record Admission(boolean admitted, boolean sharedShape, long shapeHits, String reason) {
    }
}
