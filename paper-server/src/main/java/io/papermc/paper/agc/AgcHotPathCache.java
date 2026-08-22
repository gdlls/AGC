package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * AGC — 핫 오브젝트 풀 + 청크 직렬화 캐시.
 *
 * <p>이 클래스는 GC 압박을 줄이고 hot path에서 객체 할당을 최소화하기 위한
 * 경량 캐시/카운터 컬렉션입니다. 외부 의존성 없음.</p>
 *
 * <h2>설계 원칙</h2>
 * <ul>
 *   <li><b>lock-free fast path:</b> 단순 counter는 {@link AtomicLong} CAS만 사용.</li>
 *   <li><b>sampling 기반 eviction:</b> 진짜 LRU 대신 작은 sample에서 가장 오래된
 *       후보 N개를 골라 evict. Redis maxmemory-sample과 같은 원리. O(1) amortized.</li>
 *   <li><b>바이트 사이즈 cap:</b> chunk packet 캐시는 개수 + 총 바이트 둘 다 추적.</li>
 *   <li><b>graceful degradation:</b> 캐시 사이즈 0 = 비활성. NPE나 음수 size는 0으로 보정.</li>
 * </ul>
 */
public final class AgcHotPathCache {

    private AgcHotPathCache() {}

    // =====================================================================
    // Tick-rate budget tracking (per-thread)
    // =====================================================================

    /**
     * 한 틱 안에 사용된 budget 추적.
     * {@link AgcPerformanceTuning#MAX_CHUNKS_SENT_PER_TICK} 와 함께 사용.
     *
     * <p>스레드-안전. CAS 기반 lock-free fast path. 고경합 시 fail-soft (false 반환).</p>
     */
    public static final class TickBudget {
        private final AtomicLong used = new AtomicLong();

        /**
         * @return 성공하면 true, budget 소진 시 false.
         */
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

        /**
         * {@code cost}만큼 사용량 증가. 단일 acquire와 동일하게 CAS.
         */
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
                // CAS 실패 → 재시도. 충돌 빈도는 budget 폭주 시에만 발생.
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

    // =====================================================================
    // Bounded chunk packet cache (entry-count + byte-size, sampling eviction)
    // =====================================================================

    /**
     * 청크 → 직렬화된 패킷 캐시.
     *
     * <p>개수 cap과 바이트 cap을 동시에 적용. 둘 중 먼저 도달하는 쪽이
     * eviction을 트리거. eviction은 sample-and-discard 전략: 캐시에서
     * 작은 sample을 뽑아 가장 오래된 것부터 일정 비율을 잘라낸다.</p>
     *
     * <p>eviction은 짧은 {@code synchronized} 블록에서 수행되지만 read path
     * ({@link #get}, {@link #invalidate})는 lock-free.</p>
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
            // access-time 갱신은 lock-free. eviction에서 사용.
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

        /**
         * sample-and-discard: 캐시에서 {@link AgcPerformanceTuning#CHUNK_PACKET_CACHE_EVICT_SAMPLE_SIZE}개
         * entry를 뽑고 그 중 가장 오래된 {@link AgcPerformanceTuning#CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO}
         * 비율만큼 제거.
         *
         * @return 더 evict할 게 있으면 true. 없으면 false.
         */
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

    // =====================================================================
    // Network packet counters (debug / spark)
    // =====================================================================

    /**
     * 패킷 처리 카운터 (atomic long, lock-free).
     * Spark / Timings에서 사용 가능.
     */
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

    // =====================================================================
    // Thread-local tick budget holder
    // =====================================================================

    private static final ThreadLocal<TickBudget> TICK_BUDGET = ThreadLocal.withInitial(TickBudget::new);

    public static TickBudget tickBudget() {
        return TICK_BUDGET.get();
    }

    // =====================================================================
    // Pool sizing helpers
    // =====================================================================

    /**
     * 현재 환경에서 사용할 글로벌 region thread pool 크기 추정.
     */
    public static int suggestedGlobalRegionThreads() {
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(2, (int) Math.floor(cores * AgcPerformanceTuning.GLOBAL_REGION_THREAD_POOL_RATIO));
    }

    /**
     * world-thread pool size = max(1, available cores - 1).
     * 메인 스레드와 다른 글로벌 워크로드용 코어 1개는 남겨둠.
     */
    public static int suggestedWorldThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /**
     * 현재 환경의 total worker pool 크기.
     * region + world 합산. 단, max(2, totalCores/2) 이상은 넘지 않음.
     */
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

    // =====================================================================
    // Aggregated snapshot accessor (spark / Timings)
    // =====================================================================

    private static final AtomicReference<CacheSnapshot> LAST_SNAPSHOT = new AtomicReference<>(CacheSnapshot.EMPTY);

    /**
     * @return 마지막으로 {@link #recordSnapshot()}가 호출된 시점의 캐시 스냅샷.
     *         한 번도 호출되지 않았으면 {@link CacheSnapshot#EMPTY}.
     */
    public static CacheSnapshot lastSnapshot() {
        return LAST_SNAPSHOT.get();
    }

    public static void resetSnapshot() {
        LAST_SNAPSHOT.set(CacheSnapshot.EMPTY);
    }

    /**
     * 현재의 캐시/카운터 상태를 스냅샷으로 저장. spark / Timings 폴링이 호출.
     *
     * <p>스냅샷은 {@code volatile} copy-on-write 식으로 갱신되며 호출자는
     * 일관된 값을 본다.</p>
     */
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

    /**
     * 환경 + 메모리 + 풀 사이즈의 불변 스냅샷. Timings / spark에서 사용.
     */
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
