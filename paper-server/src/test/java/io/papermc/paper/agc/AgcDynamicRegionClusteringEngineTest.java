package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AgcDynamicRegionClusteringEngineTest {

    @BeforeEach
    void setUp() {
        AgcDynamicRegionClusteringEngine.get().clear();
    }

    @Test
    void testSingleWorldDefaultCluster() {
        final AgcDynamicRegionClusteringEngine.RegionCluster cluster =
            AgcDynamicRegionClusteringEngine.get().getClusterFor("world", 0, 0);

        // Before rebalance, non-existent world returns null
        assertNull(cluster);

        // Rebalance empty player list -> creates default cluster
        AgcDynamicRegionClusteringEngine.get().rebalance("world", new long[0], 4);
        final AgcDynamicRegionClusteringEngine.RegionCluster defaultCluster =
            AgcDynamicRegionClusteringEngine.get().getClusterFor("world", 100, 100);

        assertNotNull(defaultCluster);
        assertEquals(0, defaultCluster.centerX());
        assertEquals(0, defaultCluster.centerZ());
    }

    @Test
    void testDensePlayerVoronoiSplitting() {
        // Simulate 500 players clustered across 3 distinct regions
        final long[] playerChunks = new long[500];

        // Group 1: Spawn (0, 0)
        for (int i = 0; i < 200; i++) {
            playerChunks[i] = (((long) (i % 10)) << 32) | (i % 10);
        }
        // Group 2: Warzone (1000, 1000)
        for (int i = 200; i < 350; i++) {
            playerChunks[i] = (((long) (1000 + (i % 10))) << 32) | (1000 + (i % 10));
        }
        // Group 3: Event Arena (-500, 500)
        for (int i = 350; i < 500; i++) {
            playerChunks[i] = (((long) (-500 + (i % 10))) << 32) | (500 + (i % 10));
        }

        AgcDynamicRegionClusteringEngine.get().rebalance("dense_world", playerChunks, 8);

        final List<AgcDynamicRegionClusteringEngine.RegionCluster> clusters =
            AgcDynamicRegionClusteringEngine.get().getClusters("dense_world");

        assertTrue(clusters.size() > 1, "Dense player distribution must split into multiple clusters");
        assertTrue(AgcDynamicRegionClusteringEngine.get().metrics().totalSplits() > 0);

        // Verify spatial lookup maps to closest cluster seed
        final AgcDynamicRegionClusteringEngine.RegionCluster spawnCluster =
            AgcDynamicRegionClusteringEngine.get().getClusterFor("dense_world", 5, 5);
        assertNotNull(spawnCluster);

        final AgcDynamicRegionClusteringEngine.RegionCluster warzoneCluster =
            AgcDynamicRegionClusteringEngine.get().getClusterFor("dense_world", 1005, 1005);
        assertNotNull(warzoneCluster);
        assertNotEquals(spawnCluster.id(), warzoneCluster.id());
    }
}
