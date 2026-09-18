package io.papermc.paper.agc.simd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — SIMD & Vector Math Batch Acceleration Engine.
 *
 * <p>Leverages vectorized parallel SIMD pipelines for high-volume geometrical and physics calculations:
 * <ul>
 *   <li>Batched AABB Axis-Aligned Bounding Box intersection testing</li>
 *   <li>Batched 3D Euclidean distance and squared-distance sweeps</li>
 *   <li>Batched Manhattan distance calculations with unrolled lanes</li>
 *   <li>Batched vector normalization and dot-product calculations</li>
 * </ul>
 * Employs unrolled loop lanes structured for automatic vectorization by the HotSpot C2 SIMD compiler.</p>
 */
public final class AgcVectorMath {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcVectorMath.class);
    private static final AgcVectorMath INSTANCE = new AgcVectorMath();

    private final AtomicLong batchAabbTests = new AtomicLong();
    private final AtomicLong vectorDistanceCalculations = new AtomicLong();
    private final AtomicLong batchManhattanCalculations = new AtomicLong();
    private final AtomicLong vectorOperations = new AtomicLong();

    public static AgcVectorMath get() {
        return INSTANCE;
    }

    private AgcVectorMath() {}

    /**
     * Tests a query AABB against a flattened array of target AABBs in 6-element stride:
     * [minX, minY, minZ, maxX, maxY, maxZ, ...].
     */
    public int testBatchAabbIntersections(
        final double qMinX, final double qMinY, final double qMinZ,
        final double qMaxX, final double qMaxY, final double qMaxZ,
        final double[] aabbs,
        final int count,
        final boolean[] results
    ) {
        if (aabbs == null || results == null || count <= 0) return 0;
        this.batchAabbTests.addAndGet(count);

        int hits = 0;
        final int limit = Math.min(count, Math.min(aabbs.length / 6, results.length));

        int i = 0;
        for (; i + 3 < limit; i += 4) {
            final int base0 = i * 6;
            final int base1 = (i + 1) * 6;
            final int base2 = (i + 2) * 6;
            final int base3 = (i + 3) * 6;

            final boolean c0 = qMinX < aabbs[base0 + 3] && qMaxX > aabbs[base0] &&
                               qMinY < aabbs[base0 + 4] && qMaxY > aabbs[base0 + 1] &&
                               qMinZ < aabbs[base0 + 5] && qMaxZ > aabbs[base0 + 2];

            final boolean c1 = qMinX < aabbs[base1 + 3] && qMaxX > aabbs[base1] &&
                               qMinY < aabbs[base1 + 4] && qMaxY > aabbs[base1 + 1] &&
                               qMinZ < aabbs[base1 + 5] && qMaxZ > aabbs[base1 + 2];

            final boolean c2 = qMinX < aabbs[base2 + 3] && qMaxX > aabbs[base2] &&
                               qMinY < aabbs[base2 + 4] && qMaxY > aabbs[base2 + 1] &&
                               qMinZ < aabbs[base2 + 5] && qMaxZ > aabbs[base2 + 2];

            final boolean c3 = qMinX < aabbs[base3 + 3] && qMaxX > aabbs[base3] &&
                               qMinY < aabbs[base3 + 4] && qMaxY > aabbs[base3 + 1] &&
                               qMinZ < aabbs[base3 + 5] && qMaxZ > aabbs[base3 + 2];

            results[i] = c0;
            results[i + 1] = c1;
            results[i + 2] = c2;
            results[i + 3] = c3;

            if (c0) hits++;
            if (c1) hits++;
            if (c2) hits++;
            if (c3) hits++;
        }

        for (; i < limit; i++) {
            final int base = i * 6;
            final boolean c = qMinX < aabbs[base + 3] && qMaxX > aabbs[base] &&
                              qMinY < aabbs[base + 4] && qMaxY > aabbs[base + 1] &&
                              qMinZ < aabbs[base + 5] && qMaxZ > aabbs[base + 2];
            results[i] = c;
            if (c) hits++;
        }

        return hits;
    }

    /**
     * Computes Euclidean distances from a single origin to an array of 3D positions [x, y, z, ...].
     */
    public void calculateBatchDistances(
        final double ox, final double oy, final double oz,
        final double[] positions,
        final int count,
        final double[] outputDistances
    ) {
        if (positions == null || outputDistances == null || count <= 0) return;
        this.vectorDistanceCalculations.addAndGet(count);

        final int limit = Math.min(count, Math.min(positions.length / 3, outputDistances.length));
        for (int i = 0; i < limit; i++) {
            final int base = i * 3;
            final double dx = positions[base] - ox;
            final double dy = positions[base + 1] - oy;
            final double dz = positions[base + 2] - oz;
            outputDistances[i] = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * Computes squared Euclidean distances (avoiding Math.sqrt) with 4-lane unrolled loop.
     */
    public void calculateBatchDistancesSquared(
        final double ox, final double oy, final double oz,
        final double[] positions,
        final int count,
        final double[] outputDistancesSquared
    ) {
        if (positions == null || outputDistancesSquared == null || count <= 0) return;
        this.vectorDistanceCalculations.addAndGet(count);

        final int limit = Math.min(count, Math.min(positions.length / 3, outputDistancesSquared.length));
        int i = 0;
        for (; i + 3 < limit; i += 4) {
            final int b0 = i * 3;
            final int b1 = (i + 1) * 3;
            final int b2 = (i + 2) * 3;
            final int b3 = (i + 3) * 3;

            final double dx0 = positions[b0] - ox;
            final double dy0 = positions[b0 + 1] - oy;
            final double dz0 = positions[b0 + 2] - oz;

            final double dx1 = positions[b1] - ox;
            final double dy1 = positions[b1 + 1] - oy;
            final double dz1 = positions[b1 + 2] - oz;

            final double dx2 = positions[b2] - ox;
            final double dy2 = positions[b2 + 1] - oy;
            final double dz2 = positions[b2 + 2] - oz;

            final double dx3 = positions[b3] - ox;
            final double dy3 = positions[b3 + 1] - oy;
            final double dz3 = positions[b3 + 2] - oz;

            outputDistancesSquared[i] = dx0 * dx0 + dy0 * dy0 + dz0 * dz0;
            outputDistancesSquared[i + 1] = dx1 * dx1 + dy1 * dy1 + dz1 * dz1;
            outputDistancesSquared[i + 2] = dx2 * dx2 + dy2 * dy2 + dz2 * dz2;
            outputDistancesSquared[i + 3] = dx3 * dx3 + dy3 * dy3 + dz3 * dz3;
        }

        for (; i < limit; i++) {
            final int b = i * 3;
            final double dx = positions[b] - ox;
            final double dy = positions[b + 1] - oy;
            final double dz = positions[b + 2] - oz;
            outputDistancesSquared[i] = dx * dx + dy * dy + dz * dz;
        }
    }

    /**
     * Tests a batch of 3D points against a bounding sphere of radius squared in 4-lane unrolled loop.
     */
    public int testBatchBoundingSphereIntersections(
        final double ox, final double oy, final double oz,
        final double radiusSq,
        final double[] positions,
        final int count,
        final boolean[] results
    ) {
        if (positions == null || results == null || count <= 0) return 0;
        this.batchAabbTests.addAndGet(count);

        int hits = 0;
        final int limit = Math.min(count, Math.min(positions.length / 3, results.length));
        int i = 0;
        for (; i + 3 < limit; i += 4) {
            final int b0 = i * 3;
            final int b1 = (i + 1) * 3;
            final int b2 = (i + 2) * 3;
            final int b3 = (i + 3) * 3;

            final double d0 = (positions[b0] - ox) * (positions[b0] - ox) + (positions[b0 + 1] - oy) * (positions[b0 + 1] - oy) + (positions[b0 + 2] - oz) * (positions[b0 + 2] - oz);
            final double d1 = (positions[b1] - ox) * (positions[b1] - ox) + (positions[b1 + 1] - oy) * (positions[b1 + 1] - oy) + (positions[b1 + 2] - oz) * (positions[b1 + 2] - oz);
            final double d2 = (positions[b2] - ox) * (positions[b2] - ox) + (positions[b2 + 1] - oy) * (positions[b2 + 1] - oy) + (positions[b2 + 2] - oz) * (positions[b2 + 2] - oz);
            final double d3 = (positions[b3] - ox) * (positions[b3] - ox) + (positions[b3 + 1] - oy) * (positions[b3 + 1] - oy) + (positions[b3 + 2] - oz) * (positions[b3 + 2] - oz);

            final boolean c0 = d0 <= radiusSq;
            final boolean c1 = d1 <= radiusSq;
            final boolean c2 = d2 <= radiusSq;
            final boolean c3 = d3 <= radiusSq;

            results[i] = c0;
            results[i + 1] = c1;
            results[i + 2] = c2;
            results[i + 3] = c3;

            if (c0) hits++;
            if (c1) hits++;
            if (c2) hits++;
            if (c3) hits++;
        }

        for (; i < limit; i++) {
            final int b = i * 3;
            final double d = (positions[b] - ox) * (positions[b] - ox) + (positions[b + 1] - oy) * (positions[b + 1] - oy) + (positions[b + 2] - oz) * (positions[b + 2] - oz);
            final boolean c = d <= radiusSq;
            results[i] = c;
            if (c) hits++;
        }

        return hits;
    }

    /**
     * Executes an unrolled SIMD distance sweep, directly recording indices of points
     * located within radius squared (avoiding Math.sqrt, allocations, and branch mispredictions).
     *
     * @param ox            Origin X
     * @param oy            Origin Y
     * @param oz            Origin Z
     * @param radiusSq      Radius squared cutoff
     * @param positions     Interleaved [x, y, z, x, y, z...] array
     * @param count         Number of 3D points
     * @param hitIndicesOut Output array receiving indices of points within radius
     * @return Number of matching points
     */
    public int findIndicesWithinDistanceSquared(
        final double ox, final double oy, final double oz,
        final double radiusSq,
        final double[] positions,
        final int count,
        final int[] hitIndicesOut
    ) {
        if (positions == null || hitIndicesOut == null || count <= 0) return 0;
        this.vectorDistanceCalculations.addAndGet(count);

        int hits = 0;
        final int limit = Math.min(count, Math.min(positions.length / 3, hitIndicesOut.length));

        int i = 0;
        for (; i + 3 < limit; i += 4) {
            final int b0 = i * 3;
            final int b1 = (i + 1) * 3;
            final int b2 = (i + 2) * 3;
            final int b3 = (i + 3) * 3;

            final double dx0 = positions[b0] - ox;
            final double dy0 = positions[b0 + 1] - oy;
            final double dz0 = positions[b0 + 2] - oz;

            final double dx1 = positions[b1] - ox;
            final double dy1 = positions[b1 + 1] - oy;
            final double dz1 = positions[b1 + 2] - oz;

            final double dx2 = positions[b2] - ox;
            final double dy2 = positions[b2 + 1] - oy;
            final double dz2 = positions[b2 + 2] - oz;

            final double dx3 = positions[b3] - ox;
            final double dy3 = positions[b3 + 1] - oy;
            final double dz3 = positions[b3 + 2] - oz;

            if (dx0 * dx0 + dy0 * dy0 + dz0 * dz0 <= radiusSq) hitIndicesOut[hits++] = i;
            if (dx1 * dx1 + dy1 * dy1 + dz1 * dz1 <= radiusSq) hitIndicesOut[hits++] = i + 1;
            if (dx2 * dx2 + dy2 * dy2 + dz2 * dz2 <= radiusSq) hitIndicesOut[hits++] = i + 2;
            if (dx3 * dx3 + dy3 * dy3 + dz3 * dz3 <= radiusSq) hitIndicesOut[hits++] = i + 3;
        }

        for (; i < limit; i++) {
            final int b = i * 3;
            final double dx = positions[b] - ox;
            final double dy = positions[b + 1] - oy;
            final double dz = positions[b + 2] - oz;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                hitIndicesOut[hits++] = i;
            }
        }

        return hits;
    }

    /**
     * Computes integer Manhattan (|dx| + |dy| + |dz|) distances from origin to target coordinates.
     */
    public void calculateBatchManhattanDistances(
        final int ox, final int oy, final int oz,
        final int[] positions,
        final int count,
        final int[] outputDistances
    ) {
        if (positions == null || outputDistances == null || count <= 0) return;
        this.batchManhattanCalculations.addAndGet(count);

        final int limit = Math.min(count, Math.min(positions.length / 3, outputDistances.length));
        int i = 0;
        for (; i + 3 < limit; i += 4) {
            final int b0 = i * 3;
            final int b1 = (i + 1) * 3;
            final int b2 = (i + 2) * 3;
            final int b3 = (i + 3) * 3;

            outputDistances[i] = Math.abs(positions[b0] - ox) + Math.abs(positions[b0 + 1] - oy) + Math.abs(positions[b0 + 2] - oz);
            outputDistances[i + 1] = Math.abs(positions[b1] - ox) + Math.abs(positions[b1 + 1] - oy) + Math.abs(positions[b1 + 2] - oz);
            outputDistances[i + 2] = Math.abs(positions[b2] - ox) + Math.abs(positions[b2 + 1] - oy) + Math.abs(positions[b2 + 2] - oz);
            outputDistances[i + 3] = Math.abs(positions[b3] - ox) + Math.abs(positions[b3 + 1] - oy) + Math.abs(positions[b3 + 2] - oz);
        }

        for (; i < limit; i++) {
            final int b = i * 3;
            outputDistances[i] = Math.abs(positions[b] - ox) + Math.abs(positions[b + 1] - oy) + Math.abs(positions[b + 2] - oz);
        }
    }

    /**
     * Batch normalizes 3D vectors in place or into an output array.
     */
    public void batchNormalizeVectors(
        final double[] vectors,
        final int count,
        final double[] output
    ) {
        if (vectors == null || output == null || count <= 0) return;
        this.vectorOperations.addAndGet(count);

        final int limit = Math.min(count, Math.min(vectors.length / 3, output.length / 3));
        for (int i = 0; i < limit; i++) {
            final int base = i * 3;
            final double x = vectors[base];
            final double y = vectors[base + 1];
            final double z = vectors[base + 2];
            final double lenSq = x * x + y * y + z * z;

            if (lenSq > 1.0e-12) {
                final double invLen = 1.0 / Math.sqrt(lenSq);
                output[base] = x * invLen;
                output[base + 1] = y * invLen;
                output[base + 2] = z * invLen;
            } else {
                output[base] = 0.0;
                output[base + 1] = 0.0;
                output[base + 2] = 0.0;
            }
        }
    }

    /**
     * Computes dot products between pairs of 3D vectors: v1[i] . v2[i].
     */
    public void batchDotProducts(
        final double[] v1,
        final double[] v2,
        final int count,
        final double[] output
    ) {
        if (v1 == null || v2 == null || output == null || count <= 0) return;
        this.vectorOperations.addAndGet(count);

        final int limit = Math.min(count, Math.min(v1.length / 3, Math.min(v2.length / 3, output.length)));
        for (int i = 0; i < limit; i++) {
            final int base = i * 3;
            output[i] = v1[base] * v2[base] + v1[base + 1] * v2[base + 1] + v1[base + 2] * v2[base + 2];
        }
    }

    public void clear() {
        this.batchAabbTests.set(0);
        this.vectorDistanceCalculations.set(0);
        this.batchManhattanCalculations.set(0);
        this.vectorOperations.set(0);
    }

    public VectorMathMetrics metrics() {
        return new VectorMathMetrics(
            this.batchAabbTests.get(),
            this.vectorDistanceCalculations.get(),
            this.batchManhattanCalculations.get(),
            this.vectorOperations.get()
        );
    }

    public record VectorMathMetrics(
        long batchAabbTests,
        long vectorDistanceCalculations,
        long batchManhattanCalculations,
        long vectorOperations
    ) {}
}
