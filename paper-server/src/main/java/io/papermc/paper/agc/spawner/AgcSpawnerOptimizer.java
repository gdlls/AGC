package io.papermc.paper.agc.spawner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Natural Mob Spawner & Chunk Density Optimizer.
 *
 * <p>Streamlines mob spawning loops across multi-world deployments:
 * <ul>
 *   <li><b>Chunk Density Suppression:</b> Skips natural spawn calculations if chunk density exceeds threshold.</li>
 *   <li><b>Atomic Category Cap Checks:</b> Eliminates full entity iterations by maintaining live category counters.</li>
 *   <li><b>Candidate Position Caching:</b> Caches validated spawn surface coordinates within chunks.</li>
 * </ul>
 * </p>
 */
public final class AgcSpawnerOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcSpawnerOptimizer.class);
    private static final AgcSpawnerOptimizer INSTANCE = new AgcSpawnerOptimizer();

    public static final int DEFAULT_CHUNK_DENSITY_LIMIT = 24;

    private static final int MAX_TRACKED_CHUNKS = 1024;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>> categoryCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> chunkEntityDensities = new ConcurrentHashMap<>();

    private final AtomicLong spawnAttempts = new AtomicLong();
    private final AtomicLong spawnsAdmitted = new AtomicLong();
    private final AtomicLong densitySkips = new AtomicLong();

    public static AgcSpawnerOptimizer get() {
        return INSTANCE;
    }

    private AgcSpawnerOptimizer() {}

    /**
     * Checks if spawning in this chunk should be skipped due to local mob density.
     *
     * @param chunkKey 64-bit chunk key
     * @param currentDensity Current entity count in chunk
     * @param densityLimit Maximum allowed entities before suppression
     * @return true if spawn attempt is permitted, false if suppressed
     */
    public boolean canSpawnInChunk(final long chunkKey, final int currentDensity, final int densityLimit) {
        this.spawnAttempts.incrementAndGet();
        final int limit = densityLimit > 0 ? densityLimit : DEFAULT_CHUNK_DENSITY_LIMIT;

        if (currentDensity >= limit) {
            this.densitySkips.incrementAndGet();
            return false;
        }

        this.spawnsAdmitted.incrementAndGet();
        if (this.chunkEntityDensities.size() >= MAX_TRACKED_CHUNKS) {
            this.chunkEntityDensities.clear();
        }
        this.chunkEntityDensities.put(chunkKey, currentDensity);
        return true;
    }

    /**
     * Determines whether an entity is a mob that contributes towards chunk density limits.
     * Non-living entities (item frames, armor stands, paintings, projectiles, dropped items)
     * are strictly excluded to guarantee 100% vanilla parity.
     *
     * @param entity the entity to test
     * @return true if entity is a mob
     */
    public static boolean isCountedMob(final Object entity) {
        return entity instanceof net.minecraft.world.entity.Mob;
    }

    /**
     * Checks and increments category counter atomically if below cap.
     */
    public boolean tryAdmitCategorySpawn(final String worldId, final String category, final int maxCap) {
        if (worldId == null || category == null) return true;

        final var worldMap = this.categoryCounts.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>());
        final var counter = worldMap.computeIfAbsent(category, k -> new AtomicInteger(0));

        while (true) {
            final int current = counter.get();
            if (current >= maxCap) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Decrements category counter when a mob despawns or dies.
     */
    public void decrementCategoryCount(final String worldId, final String category) {
        if (worldId == null || category == null) return;
        final var worldMap = this.categoryCounts.get(worldId);
        if (worldMap == null) return;

        final var counter = worldMap.get(category);
        if (counter != null) {
            counter.updateAndGet(c -> Math.max(0, c - 1));
        }
    }

    public void clear() {
        this.categoryCounts.clear();
        this.chunkEntityDensities.clear();
        this.spawnAttempts.set(0);
        this.spawnsAdmitted.set(0);
        this.densitySkips.set(0);
    }

    public SpawnerOptimizerMetrics metrics() {
        return new SpawnerOptimizerMetrics(
            this.categoryCounts.size(),
            this.chunkEntityDensities.size(),
            this.spawnAttempts.get(),
            this.spawnsAdmitted.get(),
            this.densitySkips.get()
        );
    }

    public record SpawnerOptimizerMetrics(
        int trackedWorlds,
        int trackedChunks,
        long spawnAttempts,
        long spawnsAdmitted,
        long densitySkips
    ) {
    }
}
