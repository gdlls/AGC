package io.papermc.paper.agc;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * AGC — High-Speed Thread-Local Hot Object Pooling Engine.
 *
 * <p>In servers with 500+ players across 50+ worlds, allocating millions of short-lived
 * vectors, bounding boxes, coordinate objects, and packet buffers every second triggers
 * frequent Young-Gen GC pauses (Stop-The-World minor GCs).</p>
 *
 * <p>This engine provides zero-allocation thread-local object recycling pools with fixed
 * capacities, ensuring zero lock contention and instantaneous acquire/release cycles.</p>
 */
public final class AgcHotObjectPool<T> {

    private final Supplier<T> factory;
    private final Consumer<T> resetter;
    private final int maxPerThread;
    private final ThreadLocal<ArrayDeque<T>> threadPool;

    // Telemetry & metrics
    private final AtomicLong acquires = new AtomicLong();
    private final AtomicLong releases = new AtomicLong();
    private final AtomicLong creations = new AtomicLong();

    public AgcHotObjectPool(final Supplier<T> factory, final Consumer<T> resetter, final int maxPerThread) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        this.factory = factory;
        this.resetter = resetter;
        this.maxPerThread = Math.max(1, maxPerThread);
        this.threadPool = ThreadLocal.withInitial(() -> new ArrayDeque<>(this.maxPerThread));
    }

    /**
     * Acquires a recycled instance from the calling thread's pool, or creates a new one if pool is empty.
     */
    public T acquire() {
        this.acquires.incrementAndGet();
        final ArrayDeque<T> pool = this.threadPool.get();
        if (!pool.isEmpty()) {
            return pool.pop();
        }
        this.creations.incrementAndGet();
        return this.factory.get();
    }

    /**
     * Releases an instance back into the calling thread's pool for future reuse.
     */
    public void release(final T object) {
        if (object == null) {
            return;
        }
        this.releases.incrementAndGet();
        if (this.resetter != null) {
            try {
                this.resetter.accept(object);
            } catch (final Throwable ignored) {}
        }
        final ArrayDeque<T> pool = this.threadPool.get();
        if (pool.size() < this.maxPerThread) {
            pool.push(object);
        }
    }

    /**
     * Clears the calling thread's local pool.
     */
    public void clearThreadLocal() {
        this.threadPool.get().clear();
    }

    public int currentThreadLocalPoolSize() {
        return this.threadPool.get().size();
    }

    public void resetMetrics() {
        this.acquires.set(0);
        this.releases.set(0);
        this.creations.set(0);
    }

    public PoolMetrics metrics() {
        return new PoolMetrics(
            this.acquires.get(),
            this.releases.get(),
            this.creations.get(),
            this.maxPerThread
        );
    }

    public record PoolMetrics(
        long acquires,
        long releases,
        long creations,
        int maxPerThread
    ) {
        public double reuseRatio() {
            if (this.acquires == 0) return 0.0;
            return (double) (this.acquires - this.creations) / (double) this.acquires;
        }
    }
}
