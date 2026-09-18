package io.papermc.paper.agc.worldgen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcJigsawBoxOctreeTest {

    private AgcJigsawBoxOctree octree;

    @BeforeEach
    public void setup() {
        this.octree = new AgcJigsawBoxOctree();
        this.octree.reset(0, 0, 0, 256, 256, 256);
    }

    @Test
    public void testEmptyOctreeNeverOverlaps() {
        assertFalse(this.octree.overlapsAny(10, 10, 10, 20, 20, 20));
        assertTrue(this.octree.fits(10, 10, 10, 20, 20, 20));
        assertEquals(0, this.octree.size());
    }

    @Test
    public void testStrictInteriorOverlapMatchesVanilla() {
        this.octree.insert(0, 0, 0, 10, 10, 10);

        // Strict interior overlap -> overlaps.
        assertTrue(this.octree.overlapsAny(5, 5, 5, 15, 15, 15));
        // Touching faces exactly -> NO overlap (vanilla ONLY_SECOND semantics).
        assertFalse(this.octree.overlapsAny(10, 0, 0, 20, 10, 10));
        assertFalse(this.octree.overlapsAny(-10, 0, 0, 0, 10, 10));
        assertFalse(this.octree.overlapsAny(0, 10, 0, 10, 20, 10));
        // Fully disjoint -> no overlap.
        assertFalse(this.octree.overlapsAny(50, 50, 50, 60, 60, 60));
        assertEquals(1, this.octree.size());
    }

    @Test
    public void testManyPiecesNearbyOnly() {
        // 200 scattered pieces; query must find the one overlapping box.
        for (int i = 0; i < 200; i++) {
            final double base = i * 1.0;
            this.octree.insert(base, 0, 0, base + 0.5, 8, 8);
        }
        assertEquals(200, this.octree.size());
        assertTrue(this.octree.overlapsAny(100.25, 1, 1, 100.75, 7, 7));
        assertFalse(this.octree.overlapsAny(100.6, 1, 1, 100.9, 7, 7));

        final var metrics = this.octree.metrics();
        // Nearby-only: boxes tested must be far fewer than total placed.
        assertTrue(metrics.boxesTested() < 200L * metrics.overlapQueries(),
            "boxesTested=" + metrics.boxesTested() + " queries=" + metrics.overlapQueries());
    }

    @Test
    public void testOutsideOuterBoundsEarlyReject() {
        this.octree.insert(100, 100, 100, 110, 110, 110);
        assertFalse(this.octree.overlapsAny(300, 300, 300, 310, 310, 310));
        assertTrue(this.octree.metrics().earlyRejects() >= 1);
    }

    @Test
    public void testBoxesOutsideInitialRootNeverMissed() {
        // First insert defines a small root; later pieces far outside it must
        // still be found (regression: root-bounds early reject would miss them).
        this.octree.insert(0, 0, 0, 1, 1, 1);
        this.octree.insert(1000, 1000, 1000, 1010, 1010, 1010);
        assertTrue(this.octree.overlapsAny(1005, 1005, 1005, 1008, 1008, 1008));
        assertTrue(this.octree.overlapsAny(0.25, 0.25, 0.25, 0.75, 0.75, 0.75));
        assertFalse(this.octree.overlapsAny(500, 500, 500, 510, 510, 510));
    }

    @Test
    public void testClear() {
        this.octree.insert(0, 0, 0, 10, 10, 10);
        this.octree.clear();
        assertEquals(0, this.octree.size());
        assertFalse(this.octree.overlapsAny(1, 1, 1, 2, 2, 2));
    }
}
