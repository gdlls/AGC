package io.papermc.paper.agc;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Storage I/O Governor & Asynchronous Save Quota Arbiter.
 *
 * <p>When 500+ worlds run on a single host, unconstrained autosave flushes can saturate
 * NVMe/disk I/O queues and cause severe MSPT spikes due to file locking and OS page cache thrashing.</p>
 *
 * <p>This governor uses a hierarchical token-bucket algorithm with 4 priority bands:
 * <ul>
 *   <li>{@link SavePriority#CRITICAL_HOT}: Player-driven dirty chunks and server shutdown flushes (always admitted)</li>
 *   <li>{@link SavePriority#HIGH_DRAIN}: Worlds draining into WARM state</li>
 *   <li>{@link SavePriority#BACKGROUND_AUTOSAVE}: Routine periodic autosaves</li>
 *   <li>{@link SavePriority#ARCHIVE_COLD}: Deep cold eviction & compaction</li>
 * </ul>
 * </p>
 */
public final class AgcStorageIoGovernor {

    private static final AgcStorageIoGovernor INSTANCE = new AgcStorageIoGovernor();

    public enum SavePriority {
        CRITICAL_HOT(4),
        HIGH_DRAIN(3),
        BACKGROUND_AUTOSAVE(2),
        ARCHIVE_COLD(1);

        final int weight;
        SavePriority(final int weight) { this.weight = weight; }
        public int weight() { return this.weight; }
    }

    private volatile double tokenCapacity = 5000.0;     // Max burst tokens (chunks)
    private volatile double refillRatePerSec = 1000.0;  // Normal sustained rate (chunks/sec)
    private volatile double tokens = 5000.0;
    private volatile long lastRefillNanos = System.nanoTime();

    private final PriorityQueue<SaveTask<?>> pendingQueue = new PriorityQueue<>(
        Comparator.<SaveTask<?>>comparingInt(t -> -t.priority.weight())
            .thenComparingLong(t -> t.enqueueTimestamp)
    );

    private final AtomicLong savesAdmitted = new AtomicLong();
    private final AtomicLong savesThrottled = new AtomicLong();
    private final AtomicLong bytesSavedEstimated = new AtomicLong();

    public static AgcStorageIoGovernor get() {
        return INSTANCE;
    }

    public AgcStorageIoGovernor() {}

    /**
     * Attempts to acquire write quota for an immediate save.
     *
     * @param priority Priority level
     * @param cost     Number of tokens (e.g. chunk count)
     * @return true if admitted immediately, false if rate-limited
     */
    public synchronized boolean tryAcquire(final SavePriority priority, final double cost) {
        refill();

        if (priority == SavePriority.CRITICAL_HOT) {
            // Critical writes always succeed, allowing temporary debt
            this.tokens -= cost;
            this.savesAdmitted.incrementAndGet();
            this.bytesSavedEstimated.addAndGet((long) (cost * 8192.0));
            return true;
        }

        if (this.tokens >= cost) {
            this.tokens -= cost;
            this.savesAdmitted.incrementAndGet();
            this.bytesSavedEstimated.addAndGet((long) (cost * 8192.0));
            return true;
        }

        this.savesThrottled.incrementAndGet();
        return false;
    }

    /**
     * Enqueues a deferred save task according to its priority band.
     */
    public synchronized <T> void enqueueSave(final String worldKey, final SavePriority priority, final T payload, final int cost) {
        this.pendingQueue.offer(new SaveTask<>(worldKey, priority, payload, cost, System.currentTimeMillis()));
    }

    /**
     * Drains pending save tasks up to available quota.
     *
     * @param maxCount Max tasks to admit in this cycle
     * @param consumer Consumer receiving admitted save tasks
     * @return Number of tasks dispatched
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> int drain(final int maxCount, final java.util.function.Consumer<SaveTask<T>> consumer) {
        if (this.pendingQueue.isEmpty() || maxCount <= 0 || consumer == null) {
            return 0;
        }

        refill();
        int dispatched = 0;

        while (!this.pendingQueue.isEmpty() && dispatched < maxCount) {
            final SaveTask<?> peek = this.pendingQueue.peek();
            if (peek == null) break;

            if (peek.priority == SavePriority.CRITICAL_HOT || this.tokens >= peek.cost) {
                final SaveTask<T> task = (SaveTask<T>) this.pendingQueue.poll();
                this.tokens -= task.cost;
                this.savesAdmitted.incrementAndGet();
                this.bytesSavedEstimated.addAndGet((long) (task.cost * 8192.0));
                try {
                    consumer.accept(task);
                } catch (final Throwable ignored) {}
                dispatched++;
            } else {
                // Not enough tokens to satisfy the highest priority item in queue
                break;
            }
        }

        return dispatched;
    }

    private void refill() {
        final long now = System.nanoTime();
        final double elapsedSec = (now - this.lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec > 0.0) {
            this.tokens = Math.min(this.tokenCapacity, this.tokens + (elapsedSec * this.refillRatePerSec));
            this.lastRefillNanos = now;
        }
    }

    public synchronized void configure(final double capacity, final double refillRate) {
        this.tokenCapacity = Math.max(1.0, capacity);
        this.refillRatePerSec = Math.max(1.0, refillRate);
        this.tokens = this.tokenCapacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized int pendingQueueSize() {
        return this.pendingQueue.size();
    }

    public synchronized double availableTokens() {
        refill();
        return this.tokens;
    }

    public synchronized void reset() {
        this.pendingQueue.clear();
        this.tokenCapacity = 5000.0;
        this.refillRatePerSec = 1000.0;
        this.tokens = 5000.0;
        this.lastRefillNanos = System.nanoTime();
        this.savesAdmitted.set(0);
        this.savesThrottled.set(0);
        this.bytesSavedEstimated.set(0);
    }

    public IoGovernorMetrics metrics() {
        return new IoGovernorMetrics(
            availableTokens(),
            pendingQueueSize(),
            this.savesAdmitted.get(),
            this.savesThrottled.get(),
            this.bytesSavedEstimated.get()
        );
    }

    public record SaveTask<T>(
        String worldKey,
        SavePriority priority,
        T payload,
        int cost,
        long enqueueTimestamp
    ) {}

    public record IoGovernorMetrics(
        double availableTokens,
        int pendingQueueSize,
        long savesAdmitted,
        long savesThrottled,
        long bytesSavedEstimated
    ) {}
}
