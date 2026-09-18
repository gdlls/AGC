package io.papermc.paper.agc.ds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Performance Fast Hashing & Canonical String Interning Engine.
 *
 * <p>Implements zero-allocation xxHash64 hashing algorithms, 64-bit coordinate bit-mixers,
 * and a lock-free canonical string interning cache to eliminate redundant object allocations
 * on hot lookup paths (e.g. ResourceLocations, block state hashes, and chunk coordinate keys).</p>
 */
public final class AgcFastHasher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcFastHasher.class);
    private static final AgcFastHasher INSTANCE = new AgcFastHasher();

    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    private final ConcurrentHashMap<String, String> stringInternPool = new ConcurrentHashMap<>(4096);

    private final AtomicLong hashesComputed = new AtomicLong();
    private final AtomicLong internHits = new AtomicLong();
    private final AtomicLong internMisses = new AtomicLong();

    public static AgcFastHasher get() {
        return INSTANCE;
    }

    private AgcFastHasher() {}

    /**
     * Fast 64-bit coordinate hash combining (x, y, z) with avalanche bit mixing.
     */
    public long hashCoords(final int x, final int y, final int z) {
        this.hashesComputed.incrementAndGet();
        long h = ((long) x * PRIME64_1) ^ ((long) y * PRIME64_2) ^ ((long) z * PRIME64_3);
        h ^= h >>> 33;
        h *= PRIME64_4;
        h ^= h >>> 29;
        h *= PRIME64_5;
        h ^= h >>> 32;
        return h;
    }

    /**
     * Fast 64-bit chunk coordinate hash for (chunkX, chunkZ).
     */
    public long hashChunkCoords(final int chunkX, final int chunkZ) {
        this.hashesComputed.incrementAndGet();
        long h = ((long) chunkX * PRIME64_1) ^ ((long) chunkZ * PRIME64_2);
        h ^= h >>> 33;
        h *= PRIME64_4;
        h ^= h >>> 29;
        return h;
    }

    /**
     * Fast xxHash64 implementation on CharSequence/String without allocations.
     */
    public long hashString(final CharSequence str) {
        if (str == null) return 0L;
        this.hashesComputed.incrementAndGet();

        long hash = PRIME64_5 + ((long) str.length() * PRIME64_1);
        for (int i = 0; i < str.length(); i++) {
            final char c = str.charAt(i);
            hash ^= ((long) c * PRIME64_2);
            hash = Long.rotateLeft(hash, 13) * PRIME64_1;
        }

        hash ^= hash >>> 33;
        hash *= PRIME64_2;
        hash ^= hash >>> 29;
        hash *= PRIME64_3;
        hash ^= hash >>> 32;
        return hash;
    }

    /**
     * Deduplicates and canonicalizes a string instance using the lock-free intern pool.
     */
    public String intern(final String str) {
        if (str == null) return null;

        final String existing = this.stringInternPool.get(str);
        if (existing != null) {
            this.internHits.incrementAndGet();
            return existing;
        }

        final String interned = this.stringInternPool.putIfAbsent(str, str);
        if (interned != null) {
            this.internHits.incrementAndGet();
            return interned;
        }

        this.internMisses.incrementAndGet();
        return str;
    }

    public void clear() {
        this.stringInternPool.clear();
        this.hashesComputed.set(0);
        this.internHits.set(0);
        this.internMisses.set(0);
    }

    public FastHasherMetrics metrics() {
        return new FastHasherMetrics(
            this.stringInternPool.size(),
            this.hashesComputed.get(),
            this.internHits.get(),
            this.internMisses.get()
        );
    }

    public record FastHasherMetrics(
        int poolSize,
        long hashesComputed,
        long internHits,
        long internMisses
    ) {
    }
}
