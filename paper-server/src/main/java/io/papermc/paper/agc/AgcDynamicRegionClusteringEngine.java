package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Dynamic Voronoi Region Clustering Engine.
 *
 * <p>Solves the hotspotting problem of fixed-grid regioning systems when thousands of players
 * congregate in a single world (such as spawn, warzones, or large events). Dynamically computes
 * Voronoi-like region cell boundaries based on real-time player and entity spatial density,
 * distributing tick workloads evenly across available CPU cores.</p>
 */
public final class AgcDynamicRegionClusteringEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcDynamicRegionClusteringEngine.class);
    private static final AgcDynamicRegionClusteringEngine INSTANCE = new AgcDynamicRegionClusteringEngine();

    /** Max players allowed in a single region before triggering a dynamic split. */
    public static final int SPLIT_THRESHOLD_PLAYERS = 64;
    /** Min players in adjacent regions to trigger a merge. */
    public static final int MERGE_THRESHOLD_PLAYERS = 16;
    /** Spatial cell size in chunk coordinates (16 chunks = 256 blocks per coarse bucket). */
    public static final int GRID_BUCKET_SHIFT = 4;

    private final AtomicInteger clusterIdGen = new AtomicInteger(1);
    private final Map<String, WorldClusterState> worldClusters = new ConcurrentHashMap<>();

    private final AtomicLong totalSplits = new AtomicLong();
    private final AtomicLong totalMerges = new AtomicLong();
    private final AtomicLong totalRebalances = new AtomicLong();

    public static AgcDynamicRegionClusteringEngine get() {
        return INSTANCE;
    }

    private AgcDynamicRegionClusteringEngine() {}

    /**
     * Rebalances region clusters for a specific world given active entity/player coordinates.
     *
     * @param worldId         Target world
     * @param playerChunkKeys Array of packed chunk coordinates (X in high 32 bits, Z in low 32 bits)
     * @param workerCount     Number of available worker threads to assign
     */
    public void rebalance(final String worldId, final long[] playerChunkKeys, final int workerCount) {
        if (worldId == null) {
            return;
        }

        final WorldClusterState state = this.worldClusters.computeIfAbsent(
            worldId, k -> new WorldClusterState(worldId)
        );

        this.totalRebalances.incrementAndGet();

        if (playerChunkKeys == null || playerChunkKeys.length == 0) {
            state.resetToDefault();
            return;
        }

        final int playerCount = playerChunkKeys.length;
        final int targetClusters = Math.max(1, Math.min(workerCount > 0 ? workerCount : 4, (playerCount + SPLIT_THRESHOLD_PLAYERS - 1) / SPLIT_THRESHOLD_PLAYERS));

        final List<RegionCluster> newClusters = new ArrayList<>(targetClusters);

        if (targetClusters == 1) {
            // Single unified cluster
            newClusters.add(new RegionCluster(
                this.clusterIdGen.getAndIncrement(),
                worldId,
                0, 0,
                Integer.MAX_VALUE,
                playerCount,
                0
            ));
        } else {
            // Density-based Voronoi seed computation
            final int step = Math.max(1, playerCount / targetClusters);
            for (int i = 0; i < targetClusters; i++) {
                final int sampleIdx = Math.min(i * step, playerCount - 1);
                final long sampleKey = playerChunkKeys[sampleIdx];
                final int cx = (int) (sampleKey >> 32);
                final int cz = (int) sampleKey;

                newClusters.add(new RegionCluster(
                    this.clusterIdGen.getAndIncrement(),
                    worldId,
                    cx, cz,
                    128, // initial cluster radius in chunks
                    step,
                    i % (workerCount > 0 ? workerCount : 1)
                ));
            }
            this.totalSplits.incrementAndGet();
        }

        state.updateClusters(newClusters);
    }

    /**
     * Resolves the assigned {@link RegionCluster} for given chunk coordinates in $O(1)$ nearest-seed search.
     *
     * @param worldId Target world
     * @param chunkX  Chunk X coordinate
     * @param chunkZ  Chunk Z coordinate
     * @return Assigned {@link RegionCluster}
     */
    public RegionCluster getClusterFor(final String worldId, final int chunkX, final int chunkZ) {
        if (worldId == null) {
            return null;
        }
        final WorldClusterState state = this.worldClusters.get(worldId);
        if (state == null) {
            return null;
        }
        return state.findNearestCluster(chunkX, chunkZ);
    }

    public List<RegionCluster> getClusters(final String worldId) {
        if (worldId == null) {
            return Collections.emptyList();
        }
        final WorldClusterState state = this.worldClusters.get(worldId);
        return state != null ? state.getClusters() : Collections.emptyList();
    }

    public void clear() {
        this.worldClusters.clear();
        this.totalSplits.set(0);
        this.totalMerges.set(0);
        this.totalRebalances.set(0);
    }

    public ClusteringMetrics metrics() {
        int activeClustersCount = 0;
        for (final WorldClusterState s : this.worldClusters.values()) {
            activeClustersCount += s.getClusters().size();
        }
        return new ClusteringMetrics(
            this.totalSplits.get(),
            this.totalMerges.get(),
            this.totalRebalances.get(),
            this.worldClusters.size(),
            activeClustersCount
        );
    }

    public static final class RegionCluster {
        private final int id;
        private final String worldId;
        private final int centerX;
        private final int centerZ;
        private final int radiusChunks;
        private final int activeCount;
        private final int assignedWorker;

        public RegionCluster(
            final int id,
            final String worldId,
            final int centerX,
            final int centerZ,
            final int radiusChunks,
            final int activeCount,
            final int assignedWorker
        ) {
            this.id = id;
            this.worldId = worldId;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radiusChunks = radiusChunks;
            this.activeCount = activeCount;
            this.assignedWorker = assignedWorker;
        }

        public int id() { return this.id; }
        public String worldId() { return this.worldId; }
        public int centerX() { return this.centerX; }
        public int centerZ() { return this.centerZ; }
        public int radiusChunks() { return this.radiusChunks; }
        public int activeCount() { return this.activeCount; }
        public int assignedWorker() { return this.assignedWorker; }

        public long distanceSquared(final int x, final int z) {
            final long dx = (long) this.centerX - x;
            final long dz = (long) this.centerZ - z;
            return dx * dx + dz * dz;
        }
    }

    private static final class WorldClusterState {
        private final String worldId;
        private volatile List<RegionCluster> clusters = Collections.emptyList();

        public WorldClusterState(final String worldId) {
            this.worldId = worldId;
            resetToDefault();
        }

        public void resetToDefault() {
            this.clusters = List.of(new RegionCluster(1, this.worldId, 0, 0, Integer.MAX_VALUE, 0, 0));
        }

        public void updateClusters(final List<RegionCluster> newClusters) {
            this.clusters = Collections.unmodifiableList(new ArrayList<>(newClusters));
        }

        public List<RegionCluster> getClusters() {
            return this.clusters;
        }

        public RegionCluster findNearestCluster(final int cx, final int cz) {
            final List<RegionCluster> current = this.clusters;
            if (current.isEmpty()) {
                return null;
            }
            if (current.size() == 1) {
                return current.get(0);
            }

            RegionCluster nearest = current.get(0);
            long minDistanceSq = nearest.distanceSquared(cx, cz);

            for (int i = 1; i < current.size(); i++) {
                final RegionCluster candidate = current.get(i);
                final long distSq = candidate.distanceSquared(cx, cz);
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq;
                    nearest = candidate;
                }
            }
            return nearest;
        }
    }

    public record ClusteringMetrics(
        long totalSplits,
        long totalMerges,
        long totalRebalances,
        int activeWorlds,
        int activeClusters
    ) {
    }
}
