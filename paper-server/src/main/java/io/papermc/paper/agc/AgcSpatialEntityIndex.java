package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * AGC — High-Performance Spatial Entity Grid Index.
 *
 * <p>Replaces linear world-wide entity searches (O(N)) for nearby entities, viewers, and
 * collision checks with a high-speed chunk-gridded spatial hash index (O(1) localized search).</p>
 */
public final class AgcSpatialEntityIndex<T> {

    private final ConcurrentHashMap<Long, List<IndexedEntity<T>>> grid = new ConcurrentHashMap<>();
    private final AtomicLong totalQueries = new AtomicLong();
    private final AtomicLong entitiesScanned = new AtomicLong();

    public AgcSpatialEntityIndex() {}

    /**
     * Adds or updates an entity in the spatial grid index.
     *
     * @param id     Entity ID
     * @param x      X coordinate
     * @param z      Z coordinate
     * @param entity The entity instance
     */
    public void put(final int id, final double x, final double z, final T entity) {
        if (entity == null) {
            return;
        }
        final int chunkX = (int) Math.floor(x) >> 4;
        final int chunkZ = (int) Math.floor(z) >> 4;
        final long key = chunkKey(chunkX, chunkZ);

        final List<IndexedEntity<T>> cell = this.grid.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        // Remove existing entry if present
        cell.removeIf(e -> e.id() == id);
        cell.add(new IndexedEntity<>(id, x, z, entity));
    }

    /**
     * Removes an entity from the spatial index.
     *
     * @param id Entity ID
     * @param x  Last known X coordinate
     * @param z  Last known Z coordinate
     */
    public void remove(final int id, final double x, final double z) {
        final int chunkX = (int) Math.floor(x) >> 4;
        final int chunkZ = (int) Math.floor(z) >> 4;
        final long key = chunkKey(chunkX, chunkZ);
        final List<IndexedEntity<T>> cell = this.grid.get(key);
        if (cell != null) {
            cell.removeIf(e -> e.id() == id);
            if (cell.isEmpty()) {
                this.grid.remove(key, cell);
            }
        }
    }

    /**
     * Queries all entities within a spherical radius around (centerX, centerZ).
     * Only scans chunks overlapping the bounding box of the radius.
     *
     * @param centerX Center X coordinate
     * @param centerZ Center Z coordinate
     * @param radius  Search radius in blocks
     * @param action  Action to perform for each matching entity
     */
    public void queryRadius(
        final double centerX,
        final double centerZ,
        final double radius,
        final Consumer<T> action
    ) {
        if (action == null || radius <= 0.0) {
            return;
        }

        this.totalQueries.incrementAndGet();
        final double radiusSq = radius * radius;

        final int minChunkX = (int) Math.floor(centerX - radius) >> 4;
        final int maxChunkX = (int) Math.floor(centerX + radius) >> 4;
        final int minChunkZ = (int) Math.floor(centerZ - radius) >> 4;
        final int maxChunkZ = (int) Math.floor(centerZ + radius) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                final List<IndexedEntity<T>> cell = this.grid.get(chunkKey(cx, cz));
                if (cell == null || cell.isEmpty()) {
                    continue;
                }

                for (final IndexedEntity<T> entry : cell) {
                    this.entitiesScanned.incrementAndGet();
                    final double dx = entry.x() - centerX;
                    final double dz = entry.z() - centerZ;
                    if ((dx * dx + dz * dz) <= radiusSq) {
                        action.accept(entry.entity());
                    }
                }
            }
        }
    }

    /**
     * Counts the total number of entities within the specified radius.
     */
    public int countInRadius(final double centerX, final double centerZ, final double radius) {
        final List<T> results = new ArrayList<>();
        this.queryRadius(centerX, centerZ, radius, results::add);
        return results.size();
    }

    public void clear() {
        this.grid.clear();
        this.totalQueries.set(0);
        this.entitiesScanned.set(0);
    }

    public int totalIndexedEntities() {
        int count = 0;
        for (final List<IndexedEntity<T>> cell : this.grid.values()) {
            count += cell.size();
        }
        return count;
    }

    public SpatialMetrics metrics() {
        return new SpatialMetrics(
            this.totalQueries.get(),
            this.entitiesScanned.get(),
            this.grid.size(),
            this.totalIndexedEntities()
        );
    }

    private static long chunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public record IndexedEntity<T>(int id, double x, double z, T entity) {
    }

    public record SpatialMetrics(
        long totalQueries,
        long entitiesScanned,
        int activeChunks,
        int totalEntities
    ) {
    }
}
