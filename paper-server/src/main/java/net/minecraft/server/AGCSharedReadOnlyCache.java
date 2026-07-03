package net.minecraft.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Tick-scoped cache for read-only duplicate computations.
 * <p>
 * This cache is deliberately not a live state cache. Entries are cleared every
 * tick and must represent immutable/read-only derived data only. It exists to
 * make large survival hotspots cheaper without changing gameplay behaviour.
 */
public final class AGCSharedReadOnlyCache {
    public static final AGCSharedReadOnlyCache INSTANCE = new AGCSharedReadOnlyCache();

    private final ConcurrentHashMap<Key, Object> entries = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    private volatile boolean enabled = true;
    private volatile int maxEntries = 524_288;
    private volatile long tickSequence;

    private AGCSharedReadOnlyCache() {
    }

    public void configure(final boolean enabled, final int maxEntries) {
        this.enabled = enabled;
        this.maxEntries = Math.max(1024, maxEntries);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.entries.clear();
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrCompute(final String namespace, final long key, final Supplier<T> supplier) {
        if (!this.enabled || supplier == null) {
            this.rejected.incrementAndGet();
            return supplier == null ? null : supplier.get();
        }
        if (this.entries.size() >= this.maxEntries) {
            this.rejected.incrementAndGet();
            return supplier.get();
        }
        final Key cacheKey = new Key(namespace == null ? "default" : namespace, key, this.tickSequence);
        final Object existing = this.entries.get(cacheKey);
        if (existing != null) {
            this.hits.incrementAndGet();
            return (T) existing;
        }
        this.misses.incrementAndGet();
        final T computed = supplier.get();
        if (computed != null) {
            this.entries.putIfAbsent(cacheKey, computed);
        }
        return computed;
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.tickSequence, this.maxEntries, this.entries.size(), this.hits.get(), this.misses.get(), this.rejected.get());
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCSharedReadOnlyCache{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", entries=" + snapshot.entries()
            + ", maxEntries=" + snapshot.maxEntries()
            + ", hits=" + snapshot.hits()
            + ", misses=" + snapshot.misses()
            + ", rejected=" + snapshot.rejected()
            + '}';
    }

    private record Key(String namespace, long key, long tick) {
    }

    public record Snapshot(boolean enabled, long tickSequence, int maxEntries, int entries, long hits, long misses, long rejected) {
    }
}
