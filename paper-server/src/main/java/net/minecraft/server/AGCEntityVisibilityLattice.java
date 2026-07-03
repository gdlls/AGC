package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only entity visibility lattice for alpha23. */
public final class AGCEntityVisibilityLattice {
    public static final AGCEntityVisibilityLattice INSTANCE = new AGCEntityVisibilityLattice();

    private final AtomicLong prepared = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile long baseNodes = 524_288L;
    private volatile long remainingNodes = 524_288L;
    private volatile String lastReason = "cold";

    private AGCEntityVisibilityLattice() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.baseNodes = Math.max(131_072L, processors * 160_000L);
        this.remainingNodes = this.baseNodes;
        this.lastReason = "reset processors=" + processors;
    }

    public Lattice prepare(final UUID playerId, final int estimatedEntities, final int observers, final String reason) {
        final int entities = Math.max(0, estimatedEntities);
        final int safeObservers = Math.max(1, observers);
        final int bands = Math.max(1, Math.min(512, (int) Math.sqrt(Math.max(1.0D, entities))));
        final long rawPairs = Math.max(1L, (long) entities * safeObservers);
        final long latticeNodes = Math.max(1L, (rawPairs / Math.max(1, bands * 2)) + bands);
        if (latticeNodes > this.remainingNodes) {
            this.ordered.incrementAndGet();
            this.lastReason = "visibility lattice saturated; tracker path remains ordered: " + safe(reason);
            return new Lattice(false, bands, rawPairs, latticeNodes, this.remainingNodes, stableKey(playerId, bands), this.lastReason);
        }
        this.remainingNodes -= latticeNodes;
        this.prepared.incrementAndGet();
        this.lastReason = "prepared read-only visibility lattice bands=" + bands + " reducedNodes=" + latticeNodes;
        return new Lattice(true, bands, rawPairs, latticeNodes, this.remainingNodes, stableKey(playerId, bands), this.lastReason);
    }

    public String statusLine() {
        return "AGCEntityVisibilityLattice{tick=" + this.tickSequence
            + ", prepared=" + this.prepared.get()
            + ", ordered=" + this.ordered.get()
            + ", baseNodes=" + this.baseNodes
            + ", remainingNodes=" + this.remainingNodes
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final UUID playerId, final int bands) {
        final long most = playerId == null ? 0L : playerId.getMostSignificantBits();
        final long least = playerId == null ? 0L : playerId.getLeastSignificantBits();
        return Long.rotateLeft(most, 5) ^ Long.rotateRight(least, 11) ^ bands;
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    public record Lattice(boolean prepared, int bands, long rawPairs, long latticeNodes, long remainingNodes, long stableKey, String reason) {}
}
