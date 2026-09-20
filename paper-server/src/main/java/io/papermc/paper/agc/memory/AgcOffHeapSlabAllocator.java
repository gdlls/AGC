package io.papermc.paper.agc.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Performance Native Off-Heap Slab Arena Allocator.
 *
 * <p>Implements a slab-based memory allocation engine using direct off-heap byte buffers.
 * Slabs are partitioned into fixed power-of-two arenas (1KB, 4KB, 16KB, 64KB).
 * Drastically eliminates native memory fragmentation and reduces JVM GC pressure by
 * keeping high-turnover I/O and chunk buffers outside the garbage collector scan graph.</p>
 */
public final class AgcOffHeapSlabAllocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcOffHeapSlabAllocator.class);
    private static final AgcOffHeapSlabAllocator INSTANCE = new AgcOffHeapSlabAllocator();

    public static final int SLAB_1K = 1024;
    public static final int SLAB_4K = 4096;
    public static final int SLAB_16K = 16384;
    public static final int SLAB_64K = 65536;

    public static final int MAX_SLABS_PER_TIER = 512;

    private final ConcurrentLinkedDeque<ByteBuffer> pool1k = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<ByteBuffer> pool4k = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<ByteBuffer> pool16k = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<ByteBuffer> pool64k = new ConcurrentLinkedDeque<>();

    private final AtomicLong totalDirectMemoryAllocated = new AtomicLong();
    private final AtomicLong slab1kAcquisitions = new AtomicLong();
    private final AtomicLong slab4kAcquisitions = new AtomicLong();
    private final AtomicLong slab16kAcquisitions = new AtomicLong();
    private final AtomicLong slab64kAcquisitions = new AtomicLong();
    private final AtomicLong totalReleases = new AtomicLong();

    public static AgcOffHeapSlabAllocator get() {
        return INSTANCE;
    }

    private AgcOffHeapSlabAllocator() {
        prewarm(this.pool1k, SLAB_1K, 32);
        prewarm(this.pool4k, SLAB_4K, 16);
        prewarm(this.pool16k, SLAB_16K, 8);
        prewarm(this.pool64k, SLAB_64K, 4);
    }

    private void prewarm(final ConcurrentLinkedDeque<ByteBuffer> pool, final int size, final int count) {
        for (int i = 0; i < count; i++) {
            pool.offer(ByteBuffer.allocateDirect(size));
            this.totalDirectMemoryAllocated.addAndGet(size);
        }
    }

    /**
     * Acquires an off-heap direct buffer matching the smallest fitting slab tier.
     * The buffer is returned in cleared state (position=0, limit=capacity).
     *
     * @param minCapacity Minimum required byte capacity
     * @return Direct ByteBuffer of at least minCapacity
     */
    public ByteBuffer acquire(final int minCapacity) {
        if (minCapacity <= SLAB_1K) {
            this.slab1kAcquisitions.incrementAndGet();
            return acquireFromPool(this.pool1k, SLAB_1K);
        } else if (minCapacity <= SLAB_4K) {
            this.slab4kAcquisitions.incrementAndGet();
            return acquireFromPool(this.pool4k, SLAB_4K);
        } else if (minCapacity <= SLAB_16K) {
            this.slab16kAcquisitions.incrementAndGet();
            return acquireFromPool(this.pool16k, SLAB_16K);
        } else if (minCapacity <= SLAB_64K) {
            this.slab64kAcquisitions.incrementAndGet();
            return acquireFromPool(this.pool64k, SLAB_64K);
        } else {
            this.totalDirectMemoryAllocated.addAndGet(minCapacity);
            return ByteBuffer.allocateDirect(minCapacity);
        }
    }

    private ByteBuffer acquireFromPool(final ConcurrentLinkedDeque<ByteBuffer> pool, final int size) {
        final ByteBuffer buf = pool.poll();
        if (buf != null) {
            buf.clear();
            return buf;
        }
        this.totalDirectMemoryAllocated.addAndGet(size);
        return ByteBuffer.allocateDirect(size);
    }

    /**
     * Releases an off-heap direct buffer back to its slab pool.
     */
    public void release(final ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) return;

        final int cap = buffer.capacity();
        buffer.clear();

        if (cap == SLAB_1K && this.pool1k.size() < MAX_SLABS_PER_TIER) {
            this.pool1k.offer(buffer);
        } else if (cap == SLAB_4K && this.pool4k.size() < MAX_SLABS_PER_TIER) {
            this.pool4k.offer(buffer);
        } else if (cap == SLAB_16K && this.pool16k.size() < MAX_SLABS_PER_TIER) {
            this.pool16k.offer(buffer);
        } else if (cap == SLAB_64K && this.pool64k.size() < MAX_SLABS_PER_TIER) {
            this.pool64k.offer(buffer);
        }

        this.totalReleases.incrementAndGet();
    }

    public void clear() {
        this.slab1kAcquisitions.set(0);
        this.slab4kAcquisitions.set(0);
        this.slab16kAcquisitions.set(0);
        this.slab64kAcquisitions.set(0);
        this.totalReleases.set(0);
    }

    /**
     * AGC — Periodic off-heap pool maintenance, invoked once per maintenance window from the
     * NMS-wired server tick tail ({@code AgcHotPathRuntimeBridge#onServerTickEnd}, gated by
     * {@code OFFHEAP_SLAB_ALLOCATOR}). Drops pooled buffers above the prewarm baseline so an
     * elytra/teleport burst cannot pin hundreds of megabytes of direct memory forever.
     * Behavior-preserving: only idle pooled buffers are dropped; live buffers are untouched.
     *
     * @return number of buffers dropped
     */
    public int trimToFit() {
        int dropped = 0;
        dropped += trimTier(this.pool1k, 32);
        dropped += trimTier(this.pool4k, 16);
        dropped += trimTier(this.pool16k, 8);
        dropped += trimTier(this.pool64k, 4);
        return dropped;
    }

    private static int trimTier(final ConcurrentLinkedDeque<ByteBuffer> pool, final int keep) {
        int dropped = 0;
        while (pool.size() > keep) {
            if (pool.poll() == null) {
                break;
            }
            dropped++;
        }
        return dropped;
    }

    public record SlabAllocatorMetrics(
        int pooled1k,
        int pooled4k,
        int pooled16k,
        int pooled64k,
        long totalDirectMemoryAllocated,
        long slab1kAcquisitions,
        long slab4kAcquisitions,
        long slab16kAcquisitions,
        long slab64kAcquisitions,
        long totalReleases
    ) {}

    public SlabAllocatorMetrics metrics() {
        return new SlabAllocatorMetrics(
            this.pool1k.size(),
            this.pool4k.size(),
            this.pool16k.size(),
            this.pool64k.size(),
            this.totalDirectMemoryAllocated.get(),
            this.slab1kAcquisitions.get(),
            this.slab4kAcquisitions.get(),
            this.slab16kAcquisitions.get(),
            this.slab64kAcquisitions.get(),
            this.totalReleases.get()
        );
    }
}
