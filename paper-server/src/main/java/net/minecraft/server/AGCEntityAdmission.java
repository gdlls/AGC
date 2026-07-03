package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Compatibility-first entity optimisation gate.
 * <p>
 * AGC entity fast paths must be byte/semantics preserving: no AI tick skipping,
 * no damage/interaction suppression, and no plugin event reordering. This class
 * gives tracker/visibility integrations a shared place to ask whether a cheap
 * read-only fast path is safe right now and records why it failed open.
 */
public final class AGCEntityAdmission {
    public static final AGCEntityAdmission INSTANCE = new AGCEntityAdmission();

    private final ConcurrentHashMap<UUID, AtomicLong> playerTrackerTokens = new ConcurrentHashMap<>();
    private final AtomicLong trackerRequests = new AtomicLong();
    private final AtomicLong trackerAdmitted = new AtomicLong();
    private final AtomicLong trackerOrderedInline = new AtomicLong();
    private volatile int perPlayerTrackerBudgetPerTick = 4096;

    private AGCEntityAdmission() {
    }

    public void configure(final int perPlayerTrackerBudgetPerTick) {
        this.perPlayerTrackerBudgetPerTick = Math.max(128, perPlayerTrackerBudgetPerTick);
    }

    /**
     * Returns true for allocation/read-only tracker fast paths. False means use
     * the original Paper entity tracking path for this operation.
     */
    public boolean admitReadOnlyTrackerFastPath(final @Nullable UUID playerId, final int estimatedEntities, final String reason) {
        this.trackerRequests.incrementAndGet();
        AGCOptimizationEnvelope.INSTANCE.admitEntityTrackingFastPath(reason);
        final AGCNoInvasionOptimizer.Decision decision = AGCNoInvasionOptimizer.INSTANCE.admitEntitySnapshot(playerId, estimatedEntities, reason);
        if (!AGCNoInvasionOptimizer.INSTANCE.isReadOnlyPrepare(decision)) {
            this.trackerOrderedInline.incrementAndGet();
            return false;
        }
        this.trackerAdmitted.incrementAndGet();
        return true;
    }

    /** Refill per-player read-only tracker budgets once per tick. */
    public void refillTickBudgets() {
        // AGCNoInvasionOptimizer.beginTick refills the shared snapshot planner.
    }

    public void forget(final UUID playerId) {
        if (playerId != null) {
            this.playerTrackerTokens.remove(playerId);
            AGCEntitySnapshotPlanner.INSTANCE.forget(playerId);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.trackerRequests.get(),
            this.trackerAdmitted.get(),
            this.trackerOrderedInline.get(),
            this.playerTrackerTokens.size(),
            this.perPlayerTrackerBudgetPerTick
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCEntityAdmission{requests=" + snapshot.requests()
            + ", admitted=" + snapshot.admitted()
            + ", orderedInline=" + snapshot.orderedInline()
            + ", trackedPlayers=" + snapshot.trackedPlayers()
            + ", perPlayerTrackerBudgetPerTick=" + snapshot.perPlayerTrackerBudgetPerTick()
            + ", snapshotPlanner=" + AGCEntitySnapshotPlanner.INSTANCE.statusLine()
            + '}';
    }

    public record Snapshot(long requests, long admitted, long orderedInline, int trackedPlayers, int perPlayerTrackerBudgetPerTick) {
    }
}
