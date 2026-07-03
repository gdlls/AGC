package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Read-only entity tracker planning budget.
 * <p>
 * It never skips entity ticks, never suppresses interaction/damage events, and
 * never changes packet contents. It only admits allocation-saving snapshot or
 * visibility pre-computation that will later be committed in the existing order.
 */
public final class AGCEntitySnapshotPlanner {
    public static final AGCEntitySnapshotPlanner INSTANCE = new AGCEntitySnapshotPlanner();

    private final ConcurrentHashMap<UUID, AtomicLong> tokens = new ConcurrentHashMap<>();
    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong orderedInline = new AtomicLong();
    private volatile int perPlayerBudgetPerTick = 4096;
    private volatile long tickSequence;

    private AGCEntitySnapshotPlanner() {
    }

    public void configure(final int perPlayerBudgetPerTick) {
        this.perPlayerBudgetPerTick = Math.max(128, perPlayerBudgetPerTick);
    }

    public void beginTick(final long tickSequence) {
        this.tickSequence = Math.max(0L, tickSequence);
        final long refill = this.perPlayerBudgetPerTick;
        for (final AtomicLong budget : this.tokens.values()) {
            budget.set(refill);
        }
    }

    public Admission admit(final @Nullable UUID playerId, final int estimatedEntities, final String reason) {
        this.requested.incrementAndGet();
        if (playerId == null) {
            this.admitted.incrementAndGet();
            return new Admission(true, Integer.MAX_VALUE, "global read-only snapshot");
        }
        final long cost = Math.max(1L, estimatedEntities);
        final AGCPlayerIntentScheduler.Admission playerIntent = AGCPlayerIntentScheduler.INSTANCE.admit(
            playerId,
            AGCPlayerIntentScheduler.IntentType.ENTITY_TRACKER_SNAPSHOT,
            (int) Math.min(Integer.MAX_VALUE, cost)
        );
        if (!playerIntent.admitted() || playerIntent.lane() != AGCPlayerIntentScheduler.Lane.READ_ONLY_PREPARE) {
            this.orderedInline.incrementAndGet();
            return new Admission(false, 0L, "player intent ordered inline");
        }
        final AtomicLong budget = this.tokens.computeIfAbsent(playerId, ignored -> new AtomicLong(this.perPlayerBudgetPerTick));
        while (true) {
            final long current = budget.get();
            if (current < cost) {
                this.orderedInline.incrementAndGet();
                return new Admission(false, current, reason == null ? "budget exhausted" : reason);
            }
            if (budget.compareAndSet(current, current - cost)) {
                this.admitted.incrementAndGet();
                return new Admission(true, current - cost, reason == null ? "read-only snapshot admitted" : reason);
            }
        }
    }

    public void forget(final UUID playerId) {
        if (playerId != null) {
            this.tokens.remove(playerId);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this.requested.get(), this.admitted.get(), this.orderedInline.get(), this.tokens.size(), this.perPlayerBudgetPerTick, this.tickSequence);
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCEntitySnapshotPlanner{requested=" + snapshot.requested()
            + ", readOnlyPrepared=" + snapshot.admitted()
            + ", orderedInline=" + snapshot.orderedInline()
            + ", trackedPlayers=" + snapshot.trackedPlayers()
            + ", budgetPerTick=" + snapshot.perPlayerBudgetPerTick()
            + ", tick=" + snapshot.tickSequence()
            + '}';
    }

    public record Admission(boolean admitted, long remainingTokens, String reason) {
    }

    public record Snapshot(long requested, long admitted, long orderedInline, int trackedPlayers, int perPlayerBudgetPerTick, long tickSequence) {
    }
}
