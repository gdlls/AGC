package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AGC — High-Performance Lock-Free Cross-World Operation Queue.
 *
 * <p>During parallel world ticking, when an operation running in World A intends to mutate
 * World B (such as portal teleports, cross-world entity transfers, or cross-world inventory sync),
 * executing inline on World A's tick thread would create a race condition with World B's tick thread.</p>
 *
 * <p>This queue captures all deferred cross-world actions and drains them safely on the primary
 * server thread immediately after the parallel world wave barrier finishes.</p>
 */
public final class AgcCrossWorldQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcCrossWorldQueue.class);
    private static final AgcCrossWorldQueue INSTANCE = new AgcCrossWorldQueue();

    private final ConcurrentLinkedQueue<DeferredOp> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalEnqueued = new AtomicLong();
    private final AtomicLong totalDrained = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong peakDepth = new AtomicLong();

    public static AgcCrossWorldQueue get() {
        return INSTANCE;
    }

    private AgcCrossWorldQueue() {}

    /**
     * Enqueues a cross-world action for safe post-barrier execution.
     *
     * @param description Short human-readable tag describing the action for diagnostics
     * @param action      The operation to execute on the primary thread
     */
    public void enqueue(final String description, final Runnable action) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        final String tag = (description == null || description.isEmpty()) ? "unnamed-op" : description;
        this.queue.offer(new DeferredOp(tag, action, System.nanoTime()));
        final long enqueued = this.totalEnqueued.incrementAndGet();
        final int currentSize = this.queue.size();

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
     * Drains all queued cross-world operations sequentially on the current thread.
     * Must be called on the primary server thread post-barrier.
     *
     * @param maxOperations Maximum operations to drain in this pass (<= 0 for unlimited)
     * @return Number of operations successfully executed
     */
    public int drain(final int maxOperations) {
        int executed = 0;
        final int limit = (maxOperations <= 0) ? Integer.MAX_VALUE : maxOperations;

        DeferredOp op;
        while (executed < limit && (op = this.queue.poll()) != null) {
            try {
                op.action().run();
                executed++;
                this.totalDrained.incrementAndGet();
            } catch (final Throwable t) {
                this.totalErrors.incrementAndGet();
                LOGGER.error("AGC Cross-World Operation [{}] failed during post-barrier drain", op.description(), t);
            }
        }
        return executed;
    }

    /**
     * Drains all pending operations without limit.
     */
    public int drainAll() {
        return this.drain(0);
    }

    /**
     * Returns true if there are operations currently waiting in the queue.
     */
    public boolean hasPending() {
        return !this.queue.isEmpty();
    }

    /**
     * Current queue depth.
     */
    public int size() {
        return this.queue.size();
    }

    /**
     * Resets internal statistics (useful for tests and profiling intervals).
     */
    public void resetMetrics() {
        this.queue.clear();
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
            this.queue.size()
        );
    }

    public record DeferredOp(String description, Runnable action, long enqueuedNanos) {
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
