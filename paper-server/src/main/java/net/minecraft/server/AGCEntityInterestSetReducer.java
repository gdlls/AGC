package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only set reducer for entity tracker candidate preparation.
 *
 * It reduces duplicate candidate calculations by band/cohort. It never skips an
 * entity tick, AI, damage, interaction or Bukkit entity event.
 */
public final class AGCEntityInterestSetReducer {
    public static final AGCEntityInterestSetReducer INSTANCE = new AGCEntityInterestSetReducer();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong reduced = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastCandidates;
    private volatile int lastReducedCandidates;
    private volatile int lastBands;
    private volatile String lastReason = "cold";

    private AGCEntityInterestSetReducer() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "tick reset";
    }

    public Reduction reduce(final UUID playerId, final int estimatedEntities, final int bands, final String reason) {
        this.requested.incrementAndGet();
        final int candidates = Math.max(0, estimatedEntities);
        if (candidates == 0) {
            this.reduced.incrementAndGet();
            this.lastCandidates = 0;
            this.lastReducedCandidates = 0;
            this.lastBands = 0;
            this.lastReason = "empty entity interest set";
            return new Reduction(true, 0, 0, 0, this.lastReason);
        }
        final int safeBands = Math.max(1, Math.min(256, bands));
        final int reducedCandidates = Math.max(1, Math.min(candidates, (candidates + safeBands - 1) / safeBands + safeBands * 2));
        final long cost = Math.max(1L, (long) reducedCandidates + safeBands);
        final AGCScale19LogicKernel.Grant grant = AGCScale19LogicKernel.INSTANCE.claim(
            AGCScale19LogicKernel.Axis.ENTITY_INTEREST,
            cost,
            "entity-interest-reduce " + safe(reason)
        );
        this.lastCandidates = candidates;
        this.lastReducedCandidates = reducedCandidates;
        this.lastBands = safeBands;
        if (!grant.admitted()) {
            this.ordered.incrementAndGet();
            this.lastReason = grant.reason();
            return new Reduction(false, candidates, candidates, safeBands, this.lastReason);
        }
        this.reduced.incrementAndGet();
        this.lastReason = "read-only interest reduced candidates=" + reducedCandidates + "/" + candidates + " bands=" + safeBands;
        return new Reduction(true, candidates, reducedCandidates, safeBands, this.lastReason);
    }

    public String statusLine() {
        return "AGCEntityInterestSetReducer{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", reduced=" + this.reduced.get()
            + ", ordered=" + this.ordered.get()
            + ", lastCandidates=" + this.lastCandidates
            + ", lastReducedCandidates=" + this.lastReducedCandidates
            + ", lastBands=" + this.lastBands
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "entity" : reason;
    }

    public record Reduction(boolean admitted, int originalCandidates, int reducedCandidates, int bands, String reason) {}
}
