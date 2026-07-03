package net.minecraft.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic two-phase pipeline used by AGC subsystems that want
 * multi-threaded preparation without changing Bukkit/Paper commit order.
 * <p>
 * A prepare task receives immutable input and may run on a worker. Its commit is
 * stored with a monotonically increasing sequence and must be drained on the
 * primary thread. The commit queue is sorted by sequence before running, so even
 * if worker completion order changes, visible game/plugin state does not.
 */
public final class AGCDeterministicTaskPipeline {
    public static final AGCDeterministicTaskPipeline INSTANCE = new AGCDeterministicTaskPipeline();

    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong scheduledPrepares = new AtomicLong();
    private final AtomicLong completedPrepares = new AtomicLong();
    private final AtomicLong committedTasks = new AtomicLong();
    private final AtomicLong failedPrepares = new AtomicLong();
    private final AtomicLong failedCommits = new AtomicLong();
    private final ConcurrentLinkedQueue<PreparedCommit> readyCommits = new ConcurrentLinkedQueue<>();
    private volatile boolean enabled = true;
    private volatile int maxCommitsPerTick = 8192;

    private AGCDeterministicTaskPipeline() {
    }

    public void configure(final boolean enabled, final int maxCommitsPerTick) {
        this.enabled = enabled;
        this.maxCommitsPerTick = Math.max(1, maxCommitsPerTick);
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public <T> long schedule(
        final String reason,
        final Callable<T> prepare,
        final java.util.function.Consumer<T> commit
    ) {
        return this.schedule(reason, prepare, commit, ForkJoinPool.commonPool());
    }

    public <T> long schedule(
        final String reason,
        final Callable<T> prepare,
        final java.util.function.Consumer<T> commit,
        final Executor executor
    ) {
        Objects.requireNonNull(prepare, "prepare");
        Objects.requireNonNull(commit, "commit");
        final long sequence = this.nextSequence.incrementAndGet();
        if (!this.enabled) {
            AGCSemanticInvariant.INSTANCE.record(
                AGCSemanticInvariant.Domain.PLUGIN,
                AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT,
                "pipeline disabled; executing inline: " + reason
            );
            try {
                commit.accept(prepare.call());
                this.committedTasks.incrementAndGet();
            } catch (final Exception exception) {
                this.failedPrepares.incrementAndGet();
                throw new RuntimeException(exception);
            }
            return sequence;
        }
        this.scheduledPrepares.incrementAndGet();
        CompletableFuture.supplyAsync(() -> {
            try {
                final T prepared = prepare.call();
                this.completedPrepares.incrementAndGet();
                AGCSemanticInvariant.INSTANCE.record(
                    AGCSemanticInvariant.Domain.WORLD_TICK,
                    AGCSemanticInvariant.Decision.READ_ONLY_PARALLEL_PREPARE,
                    reason
                );
                return prepared;
            } catch (final Throwable throwable) {
                this.failedPrepares.incrementAndGet();
                throw new RuntimeException(throwable);
            }
        }, executor == null ? ForkJoinPool.commonPool() : executor).whenComplete((prepared, throwable) -> {
            if (throwable != null) {
                AGCStabilityJournal.INSTANCE.record("deterministic-pipeline", "prepare failed for " + reason + ": " + throwable.getClass().getSimpleName());
                return;
            }
            this.readyCommits.add(new PreparedCommit(sequence, reason == null ? "unspecified" : reason, () -> commit.accept(prepared)));
        });
        return sequence;
    }

    /** Drains prepared commits in sequence order. Must be called from the main thread phase. */
    public int drainOrderedCommits() {
        final int limit = this.maxCommitsPerTick;
        final List<PreparedCommit> batch = new ArrayList<>(Math.min(limit, 1024));
        for (int i = 0; i < limit; ++i) {
            final PreparedCommit commit = this.readyCommits.poll();
            if (commit == null) {
                break;
            }
            batch.add(commit);
        }
        if (batch.isEmpty()) {
            return 0;
        }
        batch.sort(Comparator.comparingLong(PreparedCommit::sequence));
        int drained = 0;
        for (final PreparedCommit commit : batch) {
            try {
                commit.commit().run();
                this.committedTasks.incrementAndGet();
                AGCSemanticInvariant.INSTANCE.record(
                    AGCSemanticInvariant.Domain.WORLD_TICK,
                    AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT,
                    commit.reason()
                );
            } catch (final Throwable throwable) {
                this.failedCommits.incrementAndGet();
                AGCStabilityJournal.INSTANCE.record("deterministic-pipeline", "commit failed for " + commit.reason() + ": " + throwable.getClass().getSimpleName());
            }
            drained++;
        }
        return drained;
    }

    public long pendingCommits() {
        return this.readyCommits.size();
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.maxCommitsPerTick,
            this.scheduledPrepares.get(),
            this.completedPrepares.get(),
            this.committedTasks.get(),
            this.failedPrepares.get(),
            this.failedCommits.get(),
            this.pendingCommits()
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCDeterministicTaskPipeline{enabled=" + snapshot.enabled()
            + ", maxCommitsPerTick=" + snapshot.maxCommitsPerTick()
            + ", scheduled=" + snapshot.scheduledPrepares()
            + ", prepared=" + snapshot.completedPrepares()
            + ", committed=" + snapshot.committedTasks()
            + ", failedPrepare=" + snapshot.failedPrepares()
            + ", failedCommit=" + snapshot.failedCommits()
            + ", pendingCommits=" + snapshot.pendingCommits()
            + '}';
    }

    private record PreparedCommit(long sequence, String reason, Runnable commit) {
    }

    public record Snapshot(
        boolean enabled,
        int maxCommitsPerTick,
        long scheduledPrepares,
        long completedPrepares,
        long committedTasks,
        long failedPrepares,
        long failedCommits,
        long pendingCommits
    ) {
    }
}
