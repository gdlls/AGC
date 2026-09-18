package io.papermc.paper.agc.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Biome Point & Climate Search Accelerator (Lithium / C2ME port).
 *
 * <p>Caches recent 6-dimensional climate vector queries to avoid traversing the
 * R-Tree structure during repetitive terrain and entity biome lookups.</p>
 */
public final class AgcBiomeLookupCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcBiomeLookupCache.class);
    private static final AgcBiomeLookupCache INSTANCE = new AgcBiomeLookupCache();

    private final AtomicLong lookups = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();

    private static final int CACHE_SIZE = 512;
    private static final int CACHE_MASK = CACHE_SIZE - 1;

    private static final ThreadLocal<CachedEntry[]> THREAD_LOCAL_CACHE = ThreadLocal.withInitial(() -> {
        final CachedEntry[] arr = new CachedEntry[CACHE_SIZE];
        for (int i = 0; i < CACHE_SIZE; i++) {
            arr[i] = new CachedEntry();
        }
        return arr;
    });

    public static AgcBiomeLookupCache get() {
        return INSTANCE;
    }

    private AgcBiomeLookupCache() {}

    public static final class CachedEntry {
        public long t, h, c, e, d, w;
        public Holder<Biome> result;
        public boolean valid;
    }

    public Holder<Biome> getCached(final long t, final long h, final long c, final long e, final long d, final long w) {
        this.lookups.incrementAndGet();
        final int hash = (int) (t ^ (h << 5) ^ (c << 11) ^ (e << 17) ^ (d << 23) ^ (w << 29));
        final int idx = hash & CACHE_MASK;
        final CachedEntry entry = THREAD_LOCAL_CACHE.get()[idx];

        if (entry.valid && entry.t == t && entry.h == h && entry.c == c && entry.e == e && entry.d == d && entry.w == w) {
            this.hits.incrementAndGet();
            return entry.result;
        }

        return null;
    }

    public void put(final long t, final long h, final long c, final long e, final long d, final long w, final Holder<Biome> result) {
        final int hash = (int) (t ^ (h << 5) ^ (c << 11) ^ (e << 17) ^ (d << 23) ^ (w << 29));
        final int idx = hash & CACHE_MASK;
        final CachedEntry entry = THREAD_LOCAL_CACHE.get()[idx];

        entry.t = t;
        entry.h = h;
        entry.c = c;
        entry.e = e;
        entry.d = d;
        entry.w = w;
        entry.result = result;
        entry.valid = true;
    }

    public long getLookups() {
        return this.lookups.get();
    }

    public long getHits() {
        return this.hits.get();
    }
}
