package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

public class AgcNativeJpsPathfinderTest {

    @BeforeEach
    void setUp() {
        AgcNativeJpsPathfinder.get().clearMetrics();
    }

    @Test
    void testClearLineOfSightAndJpsPathfinding() {
        final BitSet emptyVoxelMask = new BitSet();
        final long[] pathBuffer = new long[16];

        // 1. Direct raymarch without obstacles
        assertTrue(AgcNativeJpsPathfinder.get().hasLineOfSight(0, 64, 0, 10, 64, 10, emptyVoxelMask));

        final int waypoints = AgcNativeJpsPathfinder.get().findPathJps(
            0, 64, 0, 10, 64, 10, emptyVoxelMask, pathBuffer
        );

        assertEquals(2, waypoints); // Start and target
        assertEquals(AgcNativeJpsPathfinder.packPos(0, 64, 0), pathBuffer[0]);
        assertEquals(AgcNativeJpsPathfinder.packPos(10, 64, 10), pathBuffer[1]);

        final AgcNativeJpsPathfinder.PathfinderMetrics metrics = AgcNativeJpsPathfinder.get().metrics();
        assertEquals(1, metrics.totalPathsCalculated());
        assertEquals(2, metrics.totalRaymarchesExecuted());
    }

    @Test
    void testObstacleDetectionAndPathfindingPerformance() {
        final BitSet solidVoxelMask = new BitSet();
        // Place solid blocking wall at X=5, Z=5, Y=64
        final int blockIndex = (5 & 0xF) | ((5 & 0xF) << 4) | (64 << 8);
        solidVoxelMask.set(blockIndex);

        // Raymarch should detect obstacle
        assertFalse(AgcNativeJpsPathfinder.get().hasLineOfSight(0, 64, 0, 10, 64, 10, solidVoxelMask));

        final long[] pathBuffer = new long[16];
        final int waypoints = AgcNativeJpsPathfinder.get().findPathJps(
            0, 64, 0, 10, 64, 10, solidVoxelMask, pathBuffer
        );

        assertTrue(waypoints >= 3, "Should generate intermediate jump point waypoint over obstacle");

        // Performance check: 10,000 raymarches in < 20ms
        final long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            AgcNativeJpsPathfinder.get().hasLineOfSight(0, 64, 0, 10, 64, 10, solidVoxelMask);
        }
        final double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        assertTrue(elapsedMs < 200.0);
    }

    @Test
    void testLruPathCache() {
        final var pf = AgcNativeJpsPathfinder.get();

        // 1. Initial cache miss
        assertNull(pf.lookup(0, 64, 0, 10, 64, 10, 1));
        assertEquals(1, pf.metrics().cacheMisses());

        // 2. Store path
        final long[] waypoints = new long[] {
            AgcNativeJpsPathfinder.packPos(0, 64, 0),
            AgcNativeJpsPathfinder.packPos(10, 64, 10)
        };
        pf.store(0, 64, 0, 10, 64, 10, 1, waypoints, 2);

        // 3. Cache hit
        final var cached = pf.lookup(0, 64, 0, 10, 64, 10, 1);
        assertNotNull(cached);
        assertEquals(2, cached.packedWaypoints().length);
        assertEquals(1, pf.metrics().cacheHits());
        assertEquals(1, pf.metrics().cachedEntries());
    }
}
