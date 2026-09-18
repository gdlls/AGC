package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Performance Priority-Tiered Lock-Free Cross-World Operation Queue.
 *
 * <p>During parallel world ticking, when an operation running in World A intends to mutate
 * World B (such as portal teleports, cross-world entity transfers, or cross-world inventory sync),
 * executing inline on World A's tick thread would create a race condition with World B's tick thread.</p>
 *
 * <p>This queue captures all deferred cross-world actions into priority-ordered queues
 * (HIGH: Portals/Players -> NORMAL: Entities/Items -> LOW: Borders/Sync) and drains them
 * safely on the primary server thread immediately after the parallel world wave barrier finishes.</p>
 */
public final class AgcCrossWorldQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcCrossWorldQueue.class);
    private static final AgcCrossWorldQueue INSTANCE = new AgcCrossWorldQueue();

    public enum Priority {
        /** Critical operations: Player portals, cross-world logins/teleports. */
        HIGH,
        /** Standard operations: Non-player entity dimensional transfers, item drops. */
        NORMAL,
        /** Non-urgent background operations: World border synchronizations, cleanups. */
        LOW
    }

    private final ConcurrentLinkedQueue<DeferredOp> highQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DeferredOp> normalQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DeferredOp> lowQueue = new ConcurrentLinkedQueue<>();

    private final AtomicLong totalEnqueued = new AtomicLong();
    private final AtomicLong totalDrained = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong peakDepth = new AtomicLong();

    public static AgcCrossWorldQueue get() {
        return INSTANCE;
    }

    private AgcCrossWorldQueue() {}

    /**
     * Enqueues a cross-world action with normal priority for safe post-barrier execution.
     */
    public void enqueue(final String description, final Runnable action) {
        this.enqueue(Priority.NORMAL, description, action);
    }

    /**
     * Enqueues a cross-world action with explicit priority for safe post-barrier execution.
     *
     * @param priority    Execution priority tier
     * @param description Short human-readable tag describing the action for diagnostics
     * @param action      The operation to execute on the primary thread
     */
    public void enqueue(final Priority priority, final String description, final Runnable action) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        final Priority p = (priority != null) ? priority : Priority.NORMAL;
        final String tag = (description == null || description.isEmpty()) ? "unnamed-op" : description;
        final DeferredOp op = new DeferredOp(p, tag, action, System.nanoTime());

        switch (p) {
            case HIGH -> this.highQueue.offer(op);
            case NORMAL -> this.normalQueue.offer(op);
            case LOW -> this.lowQueue.offer(op);
        }

        this.totalEnqueued.incrementAndGet();
        final int currentSize = size();

        // Update peak depth with lock-free loop
        long currentPeak = this.peakDepth.get();
        while (currentSize > currentPeak) {
            if (this.peakDepth.compareAndSet(currentPeak, currentSize)) {
                break;
            }
            currentPeak = this.peakDepth.get();
        }
    }

    /**
     * Drains all queued cross-world operations sequentially on the current thread in priority order.
     * Must be called on the primary server thread post-barrier.
     *
     * @param maxOperations Maximum operations to drain in this pass (&lt;= 0 for unlimited)
     * @return Number of operations successfully executed
     */
    public int drain(final int maxOperations) {
        int executed = 0;
        final int limit = (maxOperations <= 0) ? Integer.MAX_VALUE : maxOperations;

        // 1. Drain HIGH priority queue first
        executed += drainQueue(this.highQueue, limit - executed);

        // 2. Drain NORMAL priority queue
        if (executed < limit) {
            executed += drainQueue(this.normalQueue, limit - executed);
        }

        // 3. Drain LOW priority queue
        if (executed < limit) {
            executed += drainQueue(this.lowQueue, limit - executed);
        }

        return executed;
    }

    private int drainQueue(final ConcurrentLinkedQueue<DeferredOp> q, final int limit) {
        int count = 0;
        DeferredOp op;
        while (count < limit && (op = q.poll()) != null) {
            try {
                op.action().run();
                count++;
                this.totalDrained.incrementAndGet();
            } catch (final Throwable t) {
                this.totalErrors.incrementAndGet();
                LOGGER.error("AGC Cross-World Operation [{}] (priority: {}) failed during post-barrier drain",
                    op.description(), op.priority(), t);
            }
        }
        return count;
    }

    /**
     * Drains all pending operations without limit.
     */
    public int drainAll() {
        return this.drain(0);
    }

    /**
     * Returns true if there are operations currently waiting in any priority queue.
     */
    public boolean hasPending() {
        return !this.highQueue.isEmpty() || !this.normalQueue.isEmpty() || !this.lowQueue.isEmpty();
    }

    /**
     * Total current queue depth across all priority queues.
     */
    public int size() {
        return this.highQueue.size() + this.normalQueue.size() + this.lowQueue.size();
    }

    /**
     * Resets internal statistics (useful for tests and profiling intervals).
     */
    public void resetMetrics() {
        this.highQueue.clear();
        this.normalQueue.clear();
        this.lowQueue.clear();
        this.totalEnqueued.set(0);
        this.totalDrained.set(0);
        this.totalErrors.set(0);
        this.peakDepth.set(0);
    }

    /**
     * Returns a snapshot of cross-world queue performance metrics.
     */
    public QueueMetrics metrics() {
        return new QueueMetrics(
            this.totalEnqueued.get(),
            this.totalDrained.get(),
            this.totalErrors.get(),
            this.peakDepth.get(),
            size()
        );
    }

    public record DeferredOp(Priority priority, String description, Runnable action, long enqueuedNanos) {
    }

    public record QueueMetrics(
        long enqueued,
        long drained,
        long errors,
        long peakDepth,
        int currentDepth
    ) {
    }
}
