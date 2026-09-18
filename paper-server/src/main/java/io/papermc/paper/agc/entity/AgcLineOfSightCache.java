package io.papermc.paper.agc.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Line-of-Sight (LOS) Raycast Cache (Gale / Purpur port).
 *
 * <p>Caches line-of-sight visibility determinations between pairs of entities
 * for up to 4 ticks if neither entity has moved significantly (&gt; 0.25 blocks).</p>
 */
public final class AgcLineOfSightCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcLineOfSightCache.class);
    private static final AgcLineOfSightCache INSTANCE = new AgcLineOfSightCache();

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    private static final ThreadLocal<Long2ObjectOpenHashMap<CachedLos>> THREAD_LOCAL_CACHE =
        ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);

    public static AgcLineOfSightCache get() {
        return INSTANCE;
    }

    private AgcLineOfSightCache() {}

    public static final class CachedLos {
        public long tick;
        public boolean visible;
        public double srcX, srcY, srcZ;
        public double dstX, dstY, dstZ;
    }

    /**
     * Checks if a cached LOS result is valid between two entities.
     *
     * @return 1 for visible, 0 for not visible, -1 for cache miss / stale
     */
    public int getCachedLos(final LivingEntity source, final Entity target, final long currentTick) {
        final long key = (((long) source.getId()) << 32) | (target.getId() & 0xFFFFFFFFL);
        final Long2ObjectOpenHashMap<CachedLos> cache = THREAD_LOCAL_CACHE.get();
        final CachedLos entry = cache.get(key);
        if (entry == null) {
            this.cacheMisses.incrementAndGet();
            return -1;
        }

        if (currentTick - entry.tick > 4L) {
            this.cacheMisses.incrementAndGet();
            return -1;
        }

        final double sDx = source.getX() - entry.srcX;
        final double sDy = source.getY() - entry.srcY;
        final double sDz = source.getZ() - entry.srcZ;
        if (sDx * sDx + sDy * sDy + sDz * sDz > 0.0625) { // 0.25 blocks
            this.cacheMisses.incrementAndGet();
            return -1;
        }

        final double tDx = target.getX() - entry.dstX;
        final double tDy = target.getY() - entry.dstY;
        final double tDz = target.getZ() - entry.dstZ;
        if (tDx * tDx + tDy * tDy + tDz * tDz > 0.0625) {
            this.cacheMisses.incrementAndGet();
            return -1;
        }

        this.cacheHits.incrementAndGet();
        return entry.visible ? 1 : 0;
    }

    /**
     * Records a LOS result into cache.
     */
    public void putCachedLos(final LivingEntity source, final Entity target, final boolean visible, final long currentTick) {
        final long key = (((long) source.getId()) << 32) | (target.getId() & 0xFFFFFFFFL);
        final Long2ObjectOpenHashMap<CachedLos> cache = THREAD_LOCAL_CACHE.get();
        if (cache.size() > 4096) {
            cache.clear();
        }

        CachedLos entry = cache.get(key);
        if (entry == null) {
            entry = new CachedLos();
            cache.put(key, entry);
        }

        entry.tick = currentTick;
        entry.visible = visible;
        entry.srcX = source.getX();
        entry.srcY = source.getY();
        entry.srcZ = source.getZ();
        entry.dstX = target.getX();
        entry.dstY = target.getY();
        entry.dstZ = target.getZ();
    }

    public long getCacheHits() {
        return this.cacheHits.get();
    }

    public long getCacheMisses() {
        return this.cacheMisses.get();
    }
}
