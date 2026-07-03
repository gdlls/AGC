package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only entity observer matrix for tracker candidate planning. */
public final class AGCEntityObserverMatrix {
    public static final AGCEntityObserverMatrix INSTANCE = new AGCEntityObserverMatrix();

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong compacted = new AtomicLong();
    private volatile long tickSequence;
    private volatile int observerBudget = 262_144;
    private volatile String lastReason = "cold";

    private AGCEntityObserverMatrix() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.observerBudget = 262_144 + (int) Math.min(262_144L, sequence % 262_144L);
        this.lastReason = "reset observerBudget=" + this.observerBudget;
    }

    public Matrix plan(final UUID playerId, final int estimatedEntities, final int observerCohort, final String reason) {
        this.planned.incrementAndGet();
        final int entities = Math.max(0, estimatedEntities);
        final int cohort = Math.max(1, observerCohort);
        final int rawPairs = Math.max(1, entities * Math.min(64, cohort));
        final int bands = Math.max(1, Math.min(1024, (int) Math.sqrt(Math.max(1, rawPairs))));
        final int reducedPairs = Math.max(1, rawPairs / Math.max(1, bands / 2));
        final boolean admitted = reducedPairs <= this.observerBudget;
        if (admitted) {
            this.compacted.incrementAndGet();
        }
        this.lastReason = (admitted ? "read-only observer matrix" : "observer matrix waits") + " entities=" + entities + " bands=" + bands + " reducedPairs=" + reducedPairs + " reason=" + safe(reason);
        return new Matrix(admitted, rawPairs, reducedPairs, bands, this.lastReason);
    }

    public String statusLine() {
        return "AGCEntityObserverMatrix{tick=" + this.tickSequence
            + ", observerBudget=" + this.observerBudget
            + ", planned=" + this.planned.get()
            + ", compacted=" + this.compacted.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String reason) { return reason == null || reason.isBlank() ? "unspecified" : reason; }

    public record Matrix(boolean admitted, int rawPairs, int reducedPairs, int bands, String reason) {}
}
