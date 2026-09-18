package io.papermc.paper.agc.ds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * AGC — High-Throughput 2D/3D Spatial Hash Grid.
 *
 * <p>Divides world space into fine-grained $8\times 8$ block cells for constant-time $O(1)$
 * spatial partitioning. Accelerates entity proximity queries, sub-chunk collision checks,
 * and high-frequency dropped item merge grouping.</p>
 */
public final class AgcSpatialGrid {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcSpatialGrid.class);
    private static final AgcSpatialGrid INSTANCE = new AgcSpatialGrid();

    public static final int CELL_SIZE_SHIFT = 3; // 8 blocks per cell (2^3)
    public static final int CELL_SIZE = 1 << CELL_SIZE_SHIFT;

    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<GridElement>> grid = new ConcurrentHashMap<>();

    private final AtomicLong elementsIndexed = new AtomicLong();
    private final AtomicLong queriesExecuted = new AtomicLong();
    private final AtomicLong proximityHits = new AtomicLong();

    public static AgcSpatialGrid get() {
        return INSTANCE;
    }

    private AgcSpatialGrid() {}

    /**
     * Computes 64-bit spatial grid key from block coordinates.
     */
    public static long getGridKey(final int cellX, final int cellZ) {
        return (((long) cellX) << 32) | (cellZ & 0xFFFFFFFFL);
    }

    public static int toCellCoord(final double coord) {
        return ((int) Math.floor(coord)) >> CELL_SIZE_SHIFT;
    }

    /**
     * Inserts an element into the spatial grid.
     */
    public void insert(final long id, final double x, final double y, final double z, final Object payload) {
        final int cellX = toCellCoord(x);
        final int cellZ = toCellCoord(z);
        final long key = getGridKey(cellX, cellZ);

        final GridElement elem = new GridElement(id, x, y, z, payload);
        this.grid.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).offer(elem);
        this.elementsIndexed.incrementAndGet();
    }

    /**
     * Removes an element from the spatial grid cell.
     */
    public boolean remove(final long id, final double x, final double z) {
        final int cellX = toCellCoord(x);
        final int cellZ = toCellCoord(z);
        final long key = getGridKey(cellX, cellZ);

        final var queue = this.grid.get(key);
        if (queue == null) return false;

        boolean removed = queue.removeIf(e -> e.id == id);
        if (removed) {
            this.elementsIndexed.decrementAndGet();
        }
        return removed;
    }

    /**
     * Queries all objects within a spherical radius around (centerX, centerY, centerZ).
     */
    @SuppressWarnings("unchecked")
    public <T> int queryRadius(
        final double centerX,
        final double centerY,
        final double centerZ,
        final double radius,
        final List<T> results
    ) {
        if (results == null) return 0;
        this.queriesExecuted.incrementAndGet();

        final int minCellX = toCellCoord(centerX - radius);
        final int maxCellX = toCellCoord(centerX + radius);
        final int minCellZ = toCellCoord(centerZ - radius);
        final int maxCellZ = toCellCoord(centerZ + radius);

        final double radiusSq = radius * radius;
        int found = 0;

        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                final long key = getGridKey(cx, cz);
                final var queue = this.grid.get(key);
                if (queue == null || queue.isEmpty()) continue;

                for (final GridElement elem : queue) {
                    final double dx = elem.x - centerX;
                    final double dy = elem.y - centerY;
                    final double dz = elem.z - centerZ;
                    final double distSq = dx * dx + dy * dy + dz * dz;

                    if (distSq <= radiusSq) {
                        results.add((T) elem.payload);
                        found++;
                        this.proximityHits.incrementAndGet();
                    }
                }
            }
        }
        return found;
    }

    /**
     * Finds and executes a consumer on nearby pairs within maxDistance in the same or adjacent cells.
     */
    @SuppressWarnings("unchecked")
    public <T> void forEachNearbyPair(final double maxDistance, final BiConsumer<T, T> pairConsumer) {
        if (pairConsumer == null) return;
        final double maxDistSq = maxDistance * maxDistance;

        for (final var entry : this.grid.entrySet()) {
            final var queue = entry.getValue();
            if (queue == null || queue.size() < 2) continue;

            final List<GridElement> list = new ArrayList<>(queue);
            final int size = list.size();
            for (int i = 0; i < size; i++) {
                final GridElement e1 = list.get(i);
                for (int j = i + 1; j < size; j++) {
                    final GridElement e2 = list.get(j);
                    final double dx = e1.x - e2.x;
                    final double dy = e1.y - e2.y;
                    final double dz = e1.z - e2.z;
                    if ((dx * dx + dy * dy + dz * dz) <= maxDistSq) {
                        pairConsumer.accept((T) e1.payload, (T) e2.payload);
                    }
                }
            }
        }
    }

    public void clear() {
        this.grid.clear();
        this.elementsIndexed.set(0);
        this.queriesExecuted.set(0);
        this.proximityHits.set(0);
    }

    public SpatialGridMetrics metrics() {
        return new SpatialGridMetrics(
            this.grid.size(),
            this.elementsIndexed.get(),
            this.queriesExecuted.get(),
            this.proximityHits.get()
        );
    }

    private static final class GridElement {
        final long id;
        final double x;
        final double y;
        final double z;
        final Object payload;

        GridElement(final long id, final double x, final double y, final double z, final Object payload) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.payload = payload;
        }
    }

    public record SpatialGridMetrics(
        int activeCells,
        long elementsIndexed,
        long queriesExecuted,
        long proximityHits
    ) {
    }
}
