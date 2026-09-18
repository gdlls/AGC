package io.papermc.paper.agc.worldgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Structure Generation & Locational Cache Engine.
 *
 * <p>Prevents severe chunk generation stalls caused by deep structure placement searches
 * (e.g. Ancient Cities, Mansions, Fortresses):
 * <ul>
 *   <li><b>Location Cache:</b> Caches verified structure bounding starts per world seed and chunk coordinate.</li>
 *   <li><b>Concurrency Gate:</b> Limits concurrent heavy structure piece generation jobs per worker thread.</li>
 * </ul>
 * </p>
 */
public final class AgcStructureOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcStructureOptimizer.class);
    private static final AgcStructureOptimizer INSTANCE = new AgcStructureOptimizer();

    public static final int DEFAULT_MAX_CONCURRENT_STRUCTURE_BUILDS = 4;

    private final ConcurrentHashMap<String, StructureLocationEntry> structureCache = new ConcurrentHashMap<>();
    private final AtomicInteger activeStructureBuilds = new AtomicInteger(0);

    private final AtomicLong structureQueries = new AtomicLong();
    private final AtomicLong locationCacheHits = new AtomicLong();
    private final AtomicLong placementsThrottled = new AtomicLong();

    public static AgcStructureOptimizer get() {
        return INSTANCE;
    }

    private AgcStructureOptimizer() {}

    /**
     * Retrieves or evaluates structure start location.
     */
    public StructureLocationEntry getOrCreateStructureLocation(
        final String worldId,
        final String structureType,
        final int chunkX,
        final int chunkZ,
        final java.util.function.Supplier<StructureLocationEntry> locator
    ) {
        this.structureQueries.incrementAndGet();
        final String key = worldId + "#" + structureType + "#" + chunkX + "#" + chunkZ;

        final StructureLocationEntry cached = this.structureCache.get(key);
        if (cached != null) {
            this.locationCacheHits.incrementAndGet();
            return cached;
        }

        final StructureLocationEntry evaluated = locator != null ? locator.get() : null;
        if (evaluated != null) {
            this.structureCache.put(key, evaluated);
        }
        return evaluated;
    }

    /**
     * Tries to acquire a permit for heavy structure piece generation.
     */
    public boolean tryAcquireStructurePermit(final int maxPermits) {
        final int limit = maxPermits > 0 ? maxPermits : DEFAULT_MAX_CONCURRENT_STRUCTURE_BUILDS;
        while (true) {
            final int current = this.activeStructureBuilds.get();
            if (current >= limit) {
                this.placementsThrottled.incrementAndGet();
                return false;
            }
            if (this.activeStructureBuilds.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Releases an active structure generation permit.
     */
    public void releaseStructurePermit() {
        this.activeStructureBuilds.updateAndGet(c -> Math.max(0, c - 1));
    }

    public void clear() {
        this.structureCache.clear();
        this.activeStructureBuilds.set(0);
        this.structureQueries.set(0);
        this.locationCacheHits.set(0);
        this.placementsThrottled.set(0);
    }

    public StructureOptimizerMetrics metrics() {
        return new StructureOptimizerMetrics(
            this.structureCache.size(),
            this.activeStructureBuilds.get(),
            this.structureQueries.get(),
            this.locationCacheHits.get(),
            this.placementsThrottled.get()
        );
    }

    public record StructureLocationEntry(int blockX, int blockY, int blockZ, boolean hasStructure) {}

    public record StructureOptimizerMetrics(
        int cachedLocations,
        int activeBuilds,
        long structureQueries,
        long locationCacheHits,
        long placementsThrottled
    ) {
    }
}
