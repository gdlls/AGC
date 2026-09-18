package io.papermc.paper.agc.light;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Performance StarLight & Chunk Lighting Batch Optimizer.
 *
 * <p>Accelerates StarLight engine operations, sky light propagation, and block light recalculation:
 * <ul>
 *   <li><b>Nibble Array Recycling Pool:</b> Recycles 2048-byte nibble arrays, preventing young-gen GC churn during massive chunk generation and light updates.</li>
 *   <li><b>Sky Light Occlusion Bitmask:</b> 256-bit vertical column masks (four 64-bit words) for O(1) top-down sunlight occlusion checks.</li>
 *   <li><b>Light Update Coalescing:</b> Deduplicates multiple block/sky light changes targeting the same chunk section within the same tick.</li>
 *   <li><b>Batched Light Queue Draining:</b> Processes batched section light updates with zero garbage allocation.</li>
 * </ul>
 * </p>
 */
public final class AgcStarLightBatchOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcStarLightBatchOptimizer.class);
    private static final AgcStarLightBatchOptimizer INSTANCE = new AgcStarLightBatchOptimizer();

    public static final int NIBBLE_ARRAY_BYTES = 2048;
    public static final int MAX_POOLED_NIBBLES = 4096;

    // Lock-free pool of 2048-byte nibble arrays
    private final ConcurrentLinkedDeque<byte[]> nibblePool = new ConcurrentLinkedDeque<>();
    private final java.util.concurrent.atomic.AtomicInteger poolCount = new java.util.concurrent.atomic.AtomicInteger();

    // Pending section updates: packedSectionKey -> LightSectionUpdate
    private final ConcurrentHashMap<Long, LightSectionUpdate> coalescedUpdates = new ConcurrentHashMap<>();

    private final AtomicLong nibblesAcquired = new AtomicLong();
    private final AtomicLong nibblesRecycled = new AtomicLong();
    private final AtomicLong updatesCoalesced = new AtomicLong();
    private final AtomicLong skyBitmaskFastPaths = new AtomicLong();

    public static AgcStarLightBatchOptimizer get() {
        return INSTANCE;
    }

    private AgcStarLightBatchOptimizer() {
        for (int i = 0; i < 256; i++) {
            this.nibblePool.offer(new byte[NIBBLE_ARRAY_BYTES]);
        }
        this.poolCount.set(256);
    }

    /**
     * Acquires a recycled 2048-byte nibble array, or allocates a new one if pool is empty.
     * The array is guaranteed to be zero-filled.
     */
    public byte[] acquireNibbleArray() {
        this.nibblesAcquired.incrementAndGet();
        final byte[] arr = this.nibblePool.poll();
        if (arr != null) {
            this.poolCount.decrementAndGet();
            Arrays.fill(arr, (byte) 0);
            return arr;
        }
        return new byte[NIBBLE_ARRAY_BYTES];
    }

    /**
     * Recycles a 2048-byte nibble array back to the pool for reuse in O(1).
     */
    public void releaseNibbleArray(final byte[] arr) {
        if (arr != null && arr.length == NIBBLE_ARRAY_BYTES) {
            if (this.poolCount.get() < MAX_POOLED_NIBBLES) {
                this.nibblePool.offer(arr);
                this.poolCount.incrementAndGet();
                this.nibblesRecycled.incrementAndGet();
            }
        }
    }

    /**
     * Packs a chunk section coordinate into a 64-bit key:
     * [chunkX: 28 bits] [chunkZ: 28 bits] [sectionY: 8 bits]
     */
    public static long packSectionKey(final int chunkX, final int sectionY, final int chunkZ) {
        return (((long) (chunkX & 0xFFFFFFF)) << 36) |
               (((long) (chunkZ & 0xFFFFFFF)) << 8) |
               ((long) (sectionY & 0xFF));
    }

    /**
     * Coalesces a section light recalculation request. If an update is already pending
     * for the given section within this tick cycle, merges flags and returns true.
     *
     * @param chunkX Chunk X
     * @param sectionY Section Y
     * @param chunkZ Chunk Z
     * @param blockLight Whether block light is affected
     * @param skyLight Whether sky light is affected
     * @return true if merged into existing pending update, false if freshly queued
     */
    public boolean queueCoalescedSectionUpdate(
        final int chunkX,
        final int sectionY,
        final int chunkZ,
        final boolean blockLight,
        final boolean skyLight
    ) {
        final long key = packSectionKey(chunkX, sectionY, chunkZ);
        final boolean[] merged = new boolean[1];

        this.coalescedUpdates.compute(key, (k, existing) -> {
            if (existing != null) {
                existing.blockLight |= blockLight;
                existing.skyLight |= skyLight;
                this.updatesCoalesced.incrementAndGet();
                merged[0] = true;
                return existing;
            }
            return new LightSectionUpdate(chunkX, sectionY, chunkZ, blockLight, skyLight);
        });

        return merged[0];
    }

    /**
     * Drains and processes all pending coalesced light updates.
     *
     * @param consumer Consumer receiving each unique section update
     * @return Number of unique section updates processed
     */
    public int drainPendingUpdates(final java.util.function.Consumer<LightSectionUpdate> consumer) {
        if (this.coalescedUpdates.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (final Long key : this.coalescedUpdates.keySet()) {
            final LightSectionUpdate update = this.coalescedUpdates.remove(key);
            if (update != null) {
                if (consumer != null) {
                    consumer.accept(update);
                }
                count++;
            }
        }
        return count;
    }

    /**
     * 256-bit Sky Light Occlusion Bitmask for a 16x16 block column.
     * Four 64-bit words: words[0] = cols 0..63, words[1] = cols 64..127,
     * words[2] = cols 128..191, words[3] = cols 192..255.
     */
    public static final class SkyLightOcclusionMask {
        private final long[] words = new long[4];

        public void setOccluded(final int localX, final int localZ, final boolean occluded) {
            final int index = ((localZ & 0xF) << 4) | (localX & 0xF);
            final int wordIdx = index >>> 6;
            final long bit = 1L << (index & 0x3F);
            if (occluded) {
                this.words[wordIdx] |= bit;
            } else {
                this.words[wordIdx] &= ~bit;
            }
        }

        public boolean isOccluded(final int localX, final int localZ) {
            final int index = ((localZ & 0xF) << 4) | (localX & 0xF);
            final int wordIdx = index >>> 6;
            final long bit = 1L << (index & 0x3F);
            return (this.words[wordIdx] & bit) != 0;
        }

        public boolean isFullyTransparent() {
            return (this.words[0] | this.words[1] | this.words[2] | this.words[3]) == 0L;
        }

        public boolean isFullyOpaque() {
            return (this.words[0] & this.words[1] & this.words[2] & this.words[3]) == -1L;
        }

        public void clear() {
            Arrays.fill(this.words, 0L);
        }

        public void copyFrom(final SkyLightOcclusionMask other) {
            System.arraycopy(other.words, 0, this.words, 0, 4);
        }
    }

    /**
     * Fast-path check to determine if a full section is completely transparent to sunlight.
     */
    public boolean isSectionFullyTransparent(final SkyLightOcclusionMask mask) {
        if (mask != null && mask.isFullyTransparent()) {
            this.skyBitmaskFastPaths.incrementAndGet();
            return true;
        }
        return false;
    }

    public void clear() {
        this.coalescedUpdates.clear();
        this.nibblesAcquired.set(0);
        this.nibblesRecycled.set(0);
        this.updatesCoalesced.set(0);
        this.skyBitmaskFastPaths.set(0);
    }

    public static final class LightSectionUpdate {
        private final int chunkX;
        private final int sectionY;
        private final int chunkZ;
        private boolean blockLight;
        private boolean skyLight;

        public LightSectionUpdate(final int chunkX, final int sectionY, final int chunkZ, final boolean blockLight, final boolean skyLight) {
            this.chunkX = chunkX;
            this.sectionY = sectionY;
            this.chunkZ = chunkZ;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }

        public int chunkX() { return this.chunkX; }
        public int sectionY() { return this.sectionY; }
        public int chunkZ() { return this.chunkZ; }
        public boolean blockLight() { return this.blockLight; }
        public boolean skyLight() { return this.skyLight; }
    }

    public record LightOptimizerMetrics(
        int pooledNibbleCount,
        int pendingUpdateCount,
        long nibblesAcquired,
        long nibblesRecycled,
        long updatesCoalesced,
        long skyBitmaskFastPaths
    ) {}

    public LightOptimizerMetrics metrics() {
        return new LightOptimizerMetrics(
            this.nibblePool.size(),
            this.coalescedUpdates.size(),
            this.nibblesAcquired.get(),
            this.nibblesRecycled.get(),
            this.updatesCoalesced.get(),
            this.skyBitmaskFastPaths.get()
        );
    }
}
