package net.minecraft.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;

/**
 * AGC main-thread translation layer.
 * <p>
 * This is not a magic plugin compatibility bypass. It is a conservative queue
 * used by experimental AGC subsystems when a task discovered on a worker thread
 * must be replayed on the Bukkit/Paper primary thread to preserve plugin event
 * order and world mutation semantics. Keeping this layer explicit prevents
 * silent async Bukkit access while still allowing safe worker-side preparation.
 */
public final class AGCThreadTranslator {
    public static final AGCThreadTranslator INSTANCE = new AGCThreadTranslator();

    private static final int DEFAULT_MAX_DRAIN_PER_TICK = 4096;

    private final ConcurrentLinkedQueue<TranslatedTask> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong enqueuedTasks = new AtomicLong();
    private final AtomicLong drainedTasks = new AtomicLong();
    private final AtomicLong rejectedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();
    private volatile int maxDrainPerTick = DEFAULT_MAX_DRAIN_PER_TICK;
    private volatile boolean enabled = true;

    private AGCThreadTranslator() {
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxDrainPerTick() {
        return this.maxDrainPerTick;
    }

    public void setMaxDrainPerTick(final int maxDrainPerTick) {
        this.maxDrainPerTick = Math.max(1, maxDrainPerTick);
    }

    public boolean isPrimaryThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (final Throwable ignored) {
            // During bootstrap/tests Bukkit may not be fully initialised yet.
            return true;
        }
    }

    /**
     * Runs the task immediately on the primary thread, otherwise enqueues it for
     * deterministic post-barrier draining. Returns true if the task already ran.
     */
    public boolean runNowOrTranslate(final String reason, final Runnable task) {
        Objects.requireNonNull(task, "task");
        if (this.isPrimaryThread()) {
            task.run();
            return true;
        }
        if (!this.enabled) {
            this.rejectedTasks.incrementAndGet();
            return false;
        }
        this.queue.add(new TranslatedTask(System.nanoTime(), reason == null ? "unspecified" : reason, task));
        this.enqueuedTasks.incrementAndGet();
        return false;
    }

    /**
     * Drains translated work on the primary thread. Worker threads may call this
     * safely but will do nothing; this avoids accidental plugin re-entry from a
     * non-primary thread.
     */
    public int drainOnPrimaryThread() {
        if (!this.isPrimaryThread()) {
            return 0;
        }
        final int limit = this.maxDrainPerTick;
        int drained = 0;
        while (drained < limit) {
            final TranslatedTask task = this.queue.poll();
            if (task == null) {
                break;
            }
            try {
                task.task().run();
            } catch (final Throwable ignored) {
                this.failedTasks.incrementAndGet();
                AGCPerformanceGovernor.INSTANCE.disableFeatureUntilRestart(
                    AGCPerformanceGovernor.Feature.PARALLEL_WORLD_TICK,
                    "translated plugin-sensitive task failed"
                );
            } finally {
                drained++;
                this.drainedTasks.incrementAndGet();
            }
        }
        return drained;
    }

    public List<String> drainReasonsSnapshot(final int maxReasons) {
        final ArrayList<String> reasons = new ArrayList<>(Math.max(0, maxReasons));
        if (maxReasons <= 0) {
            return reasons;
        }
        int seen = 0;
        for (final TranslatedTask task : this.queue) {
            reasons.add(task.reason());
            if (++seen >= maxReasons) {
                break;
            }
        }
        return reasons;
    }

    public Snapshot snapshot() {
        final long enqueued = this.enqueuedTasks.get();
        final long drained = this.drainedTasks.get();
        final long rejected = this.rejectedTasks.get();
        final long failed = this.failedTasks.get();
        return new Snapshot(enqueued, drained, rejected, failed, Math.max(0L, enqueued - drained));
    }

    public boolean isBacklogged() {
        return this.snapshot().pendingTasks() > this.maxDrainPerTick * 2L;
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCThreadTranslator{enabled=" + this.enabled
            + ", pending=" + snapshot.pendingTasks()
            + ", enqueued=" + snapshot.enqueuedTasks()
            + ", drained=" + snapshot.drainedTasks()
            + ", rejected=" + snapshot.rejectedTasks()
            + ", failed=" + snapshot.failedTasks()
            + ", backlog=" + this.isBacklogged()
            + '}';
    }

    public static Duration nanosSince(final long nanoTime) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - nanoTime));
    }

    private record TranslatedTask(long enqueueNanoTime, String reason, Runnable task) {
    }

    public record Snapshot(long enqueuedTasks, long drainedTasks, long rejectedTasks, long failedTasks, long pendingTasks) {
    }
}
