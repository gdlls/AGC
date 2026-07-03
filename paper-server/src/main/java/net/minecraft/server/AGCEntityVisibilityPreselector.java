package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only entity visibility preselector.
 * <p>
 * It budgets candidate-set preparation for trackers. It never skips entity
 * ticks, AI, damage, interaction, activation, or plugin events. If there is no
 * read-only budget, callers use their normal ordered tracker path.
 */
public final class AGCEntityVisibilityPreselector {
    public static final AGCEntityVisibilityPreselector INSTANCE = new AGCEntityVisibilityPreselector();

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong prepared = new AtomicLong();
    private final AtomicLong directOrdered = new AtomicLong();

    private volatile boolean enabled = true;
    private volatile int maxCandidatesPerPlayer = 4096;
    private volatile long tickSequence;

    private AGCEntityVisibilityPreselector() {
    }

    public void configure(final boolean enabled, final int maxCandidatesPerPlayer) {
        this.enabled = enabled;
        this.maxCandidatesPerPlayer = Math.max(64, maxCandidatesPerPlayer);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Decision prepare(final UUID playerId, final int estimatedCandidates, final String reason) {
        this.requests.incrementAndGet();
        if (!this.enabled) {
            this.directOrdered.incrementAndGet();
            return new Decision(false, "visibility preselector disabled");
        }
        final int safeCandidates = Math.max(0, estimatedCandidates);
        if (safeCandidates > this.maxCandidatesPerPlayer) {
            this.directOrdered.incrementAndGet();
            return new Decision(false, "too many candidates for read-only preselect; preserve ordered tracker path");
        }
        final AGCComputeTopologyPlanner.Admission compute = AGCComputeTopologyPlanner.INSTANCE.claim(
            AGCComputeTopologyPlanner.Lane.ENTITY_VISIBILITY,
            Math.max(1L, safeCandidates),
            reason
        );
        if (!compute.admitted()) {
            this.directOrdered.incrementAndGet();
            return new Decision(false, compute.reason());
        }
        final AGCGlobalFairnessMatrix.Admission fairness = AGCGlobalFairnessMatrix.INSTANCE.admitPlayer(
            playerId,
            Math.max(1L, safeCandidates / 16L),
            reason
        );
        if (!fairness.admitted()) {
            this.directOrdered.incrementAndGet();
            return new Decision(false, fairness.reason());
        }
        this.prepared.incrementAndGet();
        return new Decision(true, "read-only entity candidate preselect prepared");
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.tickSequence, this.maxCandidatesPerPlayer, this.requests.get(), this.prepared.get(), this.directOrdered.get());
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCEntityVisibilityPreselector{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", maxCandidatesPerPlayer=" + snapshot.maxCandidatesPerPlayer()
            + ", requests=" + snapshot.requests()
            + ", prepared=" + snapshot.prepared()
            + ", directOrdered=" + snapshot.directOrdered()
            + '}';
    }

    public record Decision(boolean prepared, String reason) {
    }

    public record Snapshot(boolean enabled, long tickSequence, int maxCandidatesPerPlayer, long requests, long prepared, long directOrdered) {
    }
}
