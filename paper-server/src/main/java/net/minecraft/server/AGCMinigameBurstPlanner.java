package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggressive-but-safe burst planner for mini-game networks.
 * <p>
 * Arena reset/prewarm work is admitted as read-only planning or ordered commit
 * tickets. It never changes Bukkit-visible order; it only keeps thousands of
 * players from stampeding the same tick budget when many arenas start/end.
 */
public final class AGCMinigameBurstPlanner {
    public static final AGCMinigameBurstPlanner INSTANCE = new AGCMinigameBurstPlanner();

    private final ConcurrentHashMap<UUID, AtomicLong> arenaCredits = new ConcurrentHashMap<>();
    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong waited = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int arenaPrewarmBudgetPerTick = 2048;
    private volatile int arenaCommitBudgetPerTick = 1024;
    private volatile long tickSequence;

    private AGCMinigameBurstPlanner() {
    }

    public void configure(final boolean enabled, final int arenaPrewarmBudgetPerTick, final int arenaCommitBudgetPerTick) {
        this.enabled = enabled;
        this.arenaPrewarmBudgetPerTick = Math.max(1, arenaPrewarmBudgetPerTick);
        this.arenaCommitBudgetPerTick = Math.max(1, arenaCommitBudgetPerTick);
    }

    public void beginTick(final long tickSequence) {
        this.tickSequence = Math.max(0L, tickSequence);
        for (final AtomicLong credits : this.arenaCredits.values()) {
            credits.set(this.arenaPrewarmBudgetPerTick);
        }
    }

    public Admission admitPrewarm(final UUID arenaId, final int estimatedCost) {
        return this.admit(arenaId, estimatedCost, false);
    }

    public Admission admitOrderedCommit(final UUID arenaId, final int estimatedCost) {
        return this.admit(arenaId, estimatedCost, true);
    }

    private Admission admit(final UUID arenaId, final int estimatedCost, final boolean orderedCommit) {
        this.requested.incrementAndGet();
        if (!this.enabled || arenaId == null) {
            this.admitted.incrementAndGet();
            return new Admission(true, orderedCommit ? Lane.ORDERED_COMMIT : Lane.READ_ONLY_PREPARE, Integer.MAX_VALUE, "planner disabled/global");
        }
        final long cost = Math.max(1L, estimatedCost);
        final AGCTickBudgetArbiter.Admission global = AGCTickBudgetArbiter.INSTANCE.claim(
            orderedCommit ? AGCTickBudgetArbiter.Category.PLUGIN_COMMIT : AGCTickBudgetArbiter.Category.MINIGAME_PREWARM,
            cost,
            orderedCommit
        );
        if (!global.admitted()) {
            this.waited.incrementAndGet();
            return new Admission(false, Lane.FIFO_WAIT, global.remainingUnits(), global.reason());
        }
        final AtomicLong credits = this.arenaCredits.computeIfAbsent(arenaId, ignored -> new AtomicLong(this.arenaPrewarmBudgetPerTick));
        final long remaining = consume(credits, orderedCommit ? Math.min(cost, this.arenaCommitBudgetPerTick) : cost);
        if (remaining < 0L) {
            this.waited.incrementAndGet();
            return new Admission(false, Lane.FIFO_WAIT, credits.get(), "arena burst budget exhausted");
        }
        this.admitted.incrementAndGet();
        return new Admission(true, orderedCommit ? Lane.ORDERED_COMMIT : Lane.READ_ONLY_PREPARE, remaining, "arena burst admitted");
    }

    private static long consume(final AtomicLong value, final long cost) {
        while (true) {
            final long current = value.get();
            if (current < cost) {
                return -1L;
            }
            final long next = current - cost;
            if (value.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    public void forget(final UUID arenaId) {
        if (arenaId != null) {
            this.arenaCredits.remove(arenaId);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.tickSequence, this.arenaCredits.size(), this.arenaPrewarmBudgetPerTick, this.arenaCommitBudgetPerTick, this.requested.get(), this.admitted.get(), this.waited.get());
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCMinigameBurstPlanner{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", arenas=" + snapshot.arenas()
            + ", prewarmBudget=" + snapshot.arenaPrewarmBudgetPerTick()
            + ", commitBudget=" + snapshot.arenaCommitBudgetPerTick()
            + ", requested=" + snapshot.requested()
            + ", admitted=" + snapshot.admitted()
            + ", waited=" + snapshot.waited()
            + '}';
    }

    public enum Lane {
        READ_ONLY_PREPARE,
        ORDERED_COMMIT,
        FIFO_WAIT
    }

    public record Admission(boolean admitted, Lane lane, long remainingBudget, String reason) {
    }

    public record Snapshot(boolean enabled, long tickSequence, int arenas, int arenaPrewarmBudgetPerTick, int arenaCommitBudgetPerTick, long requested, long admitted, long waited) {
    }
}
