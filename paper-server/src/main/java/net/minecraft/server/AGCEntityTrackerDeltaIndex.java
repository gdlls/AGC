package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local read-only delta index for tracker candidate preparation.
 * It reduces repeated candidate scans without skipping entity ticks or events.
 */
public final class AGCEntityTrackerDeltaIndex {
    public static final AGCEntityTrackerDeltaIndex INSTANCE = new AGCEntityTrackerDeltaIndex();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong prepared = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastBands;
    private volatile int lastDeltaCount;
    private volatile String lastReason = "cold";

    private AGCEntityTrackerDeltaIndex() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastBands = 0;
        this.lastDeltaCount = 0;
        this.lastReason = "tick reset";
    }

    public Admission prepare(final UUID playerId, final int estimatedEntities, final int dirtyEntities, final String worldKey) {
        this.requested.incrementAndGet();
        final int safeEntities = Math.max(0, estimatedEntities);
        final int safeDirty = Math.max(0, Math.min(safeEntities, dirtyEntities));
        final int bands = Math.max(1, Math.min(128, 1 + Integer.highestOneBit(Math.max(1, safeEntities)) / 64));
        final long cost = Math.max(1L, safeDirty + bands);
        final AGCUnifiedTickPlanCompiler.Admission admission = AGCUnifiedTickPlanCompiler.INSTANCE.admit(
            AGCUnifiedTickPlanCompiler.Lane.ENTITY_VISIBILITY,
            cost,
            false,
            "entity-delta-index " + (worldKey == null ? "world" : worldKey)
        );
        this.lastBands = bands;
        this.lastDeltaCount = safeDirty;
        if (!admission.readOnlyPrepare()) {
            this.ordered.incrementAndGet();
            this.lastReason = admission.reason();
            return new Admission(false, bands, safeDirty, admission.reason());
        }
        this.prepared.incrementAndGet();
        this.lastReason = "read-only delta index bands=" + bands + " dirty=" + safeDirty;
        return new Admission(true, bands, safeDirty, this.lastReason);
    }

    public String statusLine() {
        return "AGCEntityTrackerDeltaIndex{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", prepared=" + this.prepared.get()
            + ", ordered=" + this.ordered.get()
            + ", lastBands=" + this.lastBands
            + ", lastDeltaCount=" + this.lastDeltaCount
            + ", lastReason='" + this.lastReason + "'}";
    }

    public record Admission(boolean prepared, int bands, int deltaCount, String reason) {}
}
