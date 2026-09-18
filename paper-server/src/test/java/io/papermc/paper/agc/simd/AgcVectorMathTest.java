package io.papermc.paper.agc.simd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcVectorMathTest {

    @BeforeEach
    public void setup() {
        AgcVectorMath.get().clear();
    }

    @Test
    public void testBatchAabbIntersections() {
        final AgcVectorMath math = AgcVectorMath.get();

        // 3 AABBs:
        // 0: [0,0,0, 2,2,2] (overlaps)
        // 1: [10,10,10, 12,12,12] (disjoint)
        // 2: [3,0,0, 5,2,2] (touching on X face x=3.0, must NOT intersect)
        final double[] aabbs = new double[]{
            0.0, 0.0, 0.0, 2.0, 2.0, 2.0,
            10.0, 10.0, 10.0, 12.0, 12.0, 12.0,
            3.0, 0.0, 0.0, 5.0, 2.0, 2.0
        };
        final boolean[] results = new boolean[3];

        // Query AABB: [1,1,1, 3,3,3] -> overlaps with AABB 0, not with AABB 1, touches AABB 2 (no intersect)
        final int hits = math.testBatchAabbIntersections(1.0, 1.0, 1.0, 3.0, 3.0, 3.0, aabbs, 3, results);

        assertEquals(1, hits);
        assertTrue(results[0]);
        assertFalse(results[1]);
        assertFalse(results[2], "Touching AABB boundary must not be classified as intersecting");
        assertEquals(3, math.metrics().batchAabbTests());
    }

    @Test
    public void testBatchDistancesAndNormalization() {
        final AgcVectorMath math = AgcVectorMath.get();

        final double[] positions = new double[]{
            3.0, 4.0, 0.0,
            0.0, 0.0, 5.0
        };
        final double[] distances = new double[2];

        math.calculateBatchDistances(0.0, 0.0, 0.0, positions, 2, distances);

        assertEquals(5.0, distances[0], 1.0e-6);
        assertEquals(5.0, distances[1], 1.0e-6);

        final double[] normalized = new double[6];
        math.batchNormalizeVectors(positions, 2, normalized);

        assertEquals(0.6, normalized[0], 1.0e-6);
        assertEquals(0.8, normalized[1], 1.0e-6);
        assertEquals(0.0, normalized[2], 1.0e-6);
        assertEquals(1.0, normalized[5], 1.0e-6);
    }

    @Test
    public void testBatchManhattanDistances() {
        final AgcVectorMath math = AgcVectorMath.get();

        final int[] positions = new int[]{
            10, 20, 30,
            -5, 0, 5
        };
        final int[] distances = new int[2];

        // Origin at (0, 0, 0)
        math.calculateBatchManhattanDistances(0, 0, 0, positions, 2, distances);

        assertEquals(60, distances[0]); // 10 + 20 + 30
        assertEquals(10, distances[1]); // 5 + 0 + 5
        assertEquals(2, math.metrics().batchManhattanCalculations());
    }

    @Test
    public void testBatchDotProducts() {
        final AgcVectorMath math = AgcVectorMath.get();

        final double[] v1 = new double[]{ 1.0, 2.0, 3.0 };
        final double[] v2 = new double[]{ 4.0, 5.0, 6.0 };
        final double[] dot = new double[1];

        math.batchDotProducts(v1, v2, 1, dot);

        // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        assertEquals(32.0, dot[0], 1.0e-6);
    }

    @Test
    public void testBatchDistancesSquaredAndBoundingSphere() {
        final AgcVectorMath math = AgcVectorMath.get();

        final double[] positions = new double[]{
            1.0, 2.0, 2.0,   // d^2 = 1 + 4 + 4 = 9
            3.0, 4.0, 0.0,   // d^2 = 9 + 16 = 25
            10.0, 10.0, 10.0 // d^2 = 300
        };
        final double[] distSq = new double[3];

        math.calculateBatchDistancesSquared(0.0, 0.0, 0.0, positions, 3, distSq);

        assertEquals(9.0, distSq[0], 1.0e-6);
        assertEquals(25.0, distSq[1], 1.0e-6);
        assertEquals(300.0, distSq[2], 1.0e-6);

        // Bounding sphere of radius 5 (radiusSq = 25.0)
        final boolean[] results = new boolean[3];
        final int hits = math.testBatchBoundingSphereIntersections(0.0, 0.0, 0.0, 25.0, positions, 3, results);

        assertEquals(2, hits);
        assertTrue(results[0]);
        assertTrue(results[1]);
        assertFalse(results[2]);
    }

    @Test
    public void testFindIndicesWithinDistanceSquared() {
        final AgcVectorMath math = AgcVectorMath.get();

        final double[] positions = new double[]{
            1.0, 2.0, 2.0,   // index 0: d^2 = 9
            3.0, 4.0, 0.0,   // index 1: d^2 = 25
            10.0, 10.0, 10.0 // index 2: d^2 = 300
        };
        final int[] hitIndices = new int[3];

        // Radius squared = 25.0 -> matches indices 0 and 1
        final int hits = math.findIndicesWithinDistanceSquared(0.0, 0.0, 0.0, 25.0, positions, 3, hitIndices);

        assertEquals(2, hits);
        assertEquals(0, hitIndices[0]);
        assertEquals(1, hitIndices[1]);
    }
}
