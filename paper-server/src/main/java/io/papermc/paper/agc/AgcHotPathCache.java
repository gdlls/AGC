package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * AGC — Hot object pools and serialized chunk packet cache.
 */
public final class AgcHotPathCache {

    private AgcHotPathCache() {}


    /**
     * Thread-safe CAS-based tick budget tracker.
     */
    public static final class TickBudget {
        private final AtomicLong used = new AtomicLong();

        public boolean tryAcquire(final int maxPerTick) {
            if (maxPerTick <= 0) {
                return true;
            }
            final long cur = this.used.get();
            if (cur >= maxPerTick) {
                return false;
            }
            return this.used.compareAndSet(cur, cur + 1);
        }

        public boolean tryAcquire(final int maxPerTick, final int cost) {
            if (maxPerTick <= 0 || cost <= 0) {
                return true;
            }
            while (true) {
                final long cur = this.used.get();
                if (cur + cost > maxPerTick) {
                    return false;
                }
                if (this.used.compareAndSet(cur, cur + cost)) {
                    return true;
                }
            }
        }

        public int remaining(final int maxPerTick) {
            if (maxPerTick <= 0) {
                return 0;
            }
            return (int) Math.max(0, maxPerTick - this.used.get());
        }

        public long used() {
            return this.used.get();
        }

        public void reset() {
            this.used.set(0);
        }
    }


    /**
     * Serialized chunk packet cache with dual entry-count and byte-size caps.
     */
    public static final class ChunkPacketCache {
        private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();
        private final int maxEntries;
        private final long maxBytes;
        private final AtomicLong currentBytes = new AtomicLong();
        private final Object evictionLock = new Object();
        // monotonic counter for LRU approximation
        private final AtomicLong tickCounter = new AtomicLong();

        public ChunkPacketCache() {
            this(
                AgcPerformanceTuning.CHUNK_PACKET_CACHE_MAX_ENTRIES,
                AgcPerformanceTuning.CHUNK_PACKET_CACHE_MAX_BYTES
            );
        }

        public ChunkPacketCache(final int maxEntries, final long maxBytes) {
            this.maxEntries = Math.max(0, maxEntries);
            this.maxBytes = Math.max(0L, maxBytes);
        }

        public byte[] get(final int chunkX, final int chunkZ) {
            if (this.maxEntries == 0) {
                return null;
            }
            final Entry entry = this.cache.get(key(chunkX, chunkZ));
            if (entry == null) {
                return null;
            }
            // Lock-free access-time update for sampling eviction.
            entry.lastAccessTick = this.tickCounter.incrementAndGet();
            return entry.data;
        }

        public void put(final int chunkX, final int chunkZ, final byte[] data) {
            if (this.maxEntries == 0 || data == null) {
                return;
            }
            final long k = key(chunkX, chunkZ);
            final long tick = this.tickCounter.incrementAndGet();
            final Entry newEntry = new Entry(k, data, tick);
            final Entry old = this.cache.put(k, newEntry);
            if (old != null) {
                this.currentBytes.addAndGet(data.length - old.data.length);
            } else {
                this.currentBytes.addAndGet(data.length);
            }
            if (this.shouldEvict()) {
                synchronized (this.evictionLock) {
                    this.evictIfNeededLocked();
                }
            }
        }

        public void invalidate(final int chunkX, final int chunkZ) {
            final Entry removed = this.cache.remove(key(chunkX, chunkZ));
            if (removed != null) {
                this.currentBytes.addAndGet(-removed.data.length);
            }
        }

        public void invalidateAll() {
            this.cache.clear();
            this.currentBytes.set(0);
        }

        public int size() {
            return this.cache.size();
        }

        public long currentBytes() {
            return this.currentBytes.get();
        }

        public int maxEntries() {
            return this.maxEntries;
        }

        public long maxBytes() {
            return this.maxBytes;
        }

        private boolean shouldEvict() {
            if (this.maxEntries > 0 && this.cache.size() > this.maxEntries) {
                return true;
            }
            return this.maxBytes > 0 && this.currentBytes.get() > this.maxBytes;
        }

        private void evictIfNeededLocked() {
            // entry cap
            while (this.maxEntries > 0 && this.cache.size() > this.maxEntries) {
                if (!evictOneBatchLocked()) {
                    break;
                }
            }
            // byte cap
            while (this.maxBytes > 0 && this.currentBytes.get() > this.maxBytes) {
                if (!evictOneBatchLocked()) {
                    break;
                }
            }
        }

        private boolean evictOneBatchLocked() {
            final int sampleSize = Math.min(AgcPerformanceTuning.CHUNK_PACKET_CACHE_EVICT_SAMPLE_SIZE, this.cache.size());
            if (sampleSize <= 0) {
                return false;
            }
            final int toDrop = Math.max(1, sampleSize * AgcPerformanceTuning.CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_NUMERATOR
                / AgcPerformanceTuning.CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_DENOM);
            final List<Entry> sample = new ArrayList<>(sampleSize);
            final Iterator<Entry> it = this.cache.values().iterator();
            for (int i = 0; i < sampleSize && it.hasNext(); i++) {
                sample.add(it.next());
            }
            if (sample.isEmpty()) {
                return false;
            }
            // sort by lastAccessTick ascending (oldest first)
            sample.sort((a, b) -> Long.compare(a.lastAccessTick, b.lastAccessTick));
            int dropped = 0;
            for (int i = 0; i < toDrop && i < sample.size(); i++) {
                final Entry victim = sample.get(i);
                if (this.cache.remove(victim.key) != null) {
                    this.currentBytes.addAndGet(-victim.data.length);
                    dropped++;
                }
            }
            return dropped > 0;
        }

        private static long key(final int chunkX, final int chunkZ) {
            return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        }

        private static final class Entry {
            final long key;
            final byte[] data;
            volatile long lastAccessTick;

            Entry(final long key, final byte[] data, final long tick) {
                this.key = key;
                this.data = data;
                this.lastAccessTick = tick;
            }
        }
    }


    /** Lock-free packet processing counters for telemetry. */
    public static final class PacketCounter {
        private final AtomicLong processed = new AtomicLong();
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong bytes = new AtomicLong();

        public void recordProcessed(final int byteCount) {
            this.processed.incrementAndGet();
            if (byteCount > 0) {
                this.bytes.addAndGet(byteCount);
            }
        }

        public void recordDropped() {
            this.dropped.incrementAndGet();
        }

        public long processed() { return this.processed.get(); }
        public long dropped()   { return this.dropped.get(); }
        public long bytes()     { return this.bytes.get(); }

        public void reset() {
            this.processed.set(0);
            this.dropped.set(0);
            this.bytes.set(0);
        }
    }


    private static final ThreadLocal<TickBudget> TICK_BUDGET = ThreadLocal.withInitial(TickBudget::new);

    public static TickBudget tickBudget() {
        return TICK_BUDGET.get();
    }


    /** Estimated worker count for global region thread pool. */
    public static int suggestedGlobalRegionThreads() {
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(2, (int) Math.floor(cores * AgcPerformanceTuning.GLOBAL_REGION_THREAD_POOL_RATIO));
    }

    /** Estimated worker count for world-parallel thread pool. */
    public static int suggestedWorldThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /** Total worker pool thread count across region and world tasks. */
    public static int suggestedTotalWorkerThreads() {
        final int region = suggestedGlobalRegionThreads();
        final int world = suggestedWorldThreads();
        final int total = region + world;
        final int cap = Math.max(2, Runtime.getRuntime().availableProcessors());
        return Math.min(total, cap);
    }

    /**
     * Monotonic high-resolution nano-clock supplier.
     */
    public static LongSupplier systemNanoClock() {
        return System::nanoTime;
    }


    private static final AtomicReference<CacheSnapshot> LAST_SNAPSHOT = new AtomicReference<>(CacheSnapshot.EMPTY);

    /** Returns the most recently captured cache snapshot, or EMPTY. */
    public static CacheSnapshot lastSnapshot() {
        return LAST_SNAPSHOT.get();
    }

    public static void resetSnapshot() {
        LAST_SNAPSHOT.set(CacheSnapshot.EMPTY);
    }

    /** Captures and publishes a fresh snapshot of JVM metrics. */
    public static CacheSnapshot recordSnapshot() {
        final CacheSnapshot snap = new CacheSnapshot(
            System.nanoTime(),
            Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().freeMemory(),
            Runtime.getRuntime().totalMemory(),
            Runtime.getRuntime().maxMemory()
        );
        LAST_SNAPSHOT.set(snap);
        return snap;
    }

    /** Immutable snapshot of JVM memory and runtime topology. */
    public record CacheSnapshot(
        long nanoTime,
        int availableProcessors,
        long freeMemory,
        long totalMemory,
        long maxMemory
    ) {
        public static final CacheSnapshot EMPTY = new CacheSnapshot(0L, 0, 0L, 0L, 0L);

        public double usedMemoryRatio() {
            if (this.maxMemory == 0L) return 0.0;
            return (double) (this.totalMemory - this.freeMemory) / (double) this.maxMemory;
        }
    }
}
