package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcSimdCollisionKernelTest {

    @BeforeEach
    void setUp() {
        AgcSimdCollisionKernel.get().clearMetrics();
    }

    @Test
    void testSweep64HitsDetection() {
        final int candidateCount = 64;
        final float[] minX = new float[candidateCount];
        final float[] minY = new float[candidateCount];
        final float[] minZ = new float[candidateCount];
        final float[] maxX = new float[candidateCount];
        final float[] maxY = new float[candidateCount];
        final float[] maxZ = new float[candidateCount];
        final int[] hitIndices = new int[candidateCount];

        // Setup candidate boxes: every even index intersects with target box at [0,0,0 -> 2,2,2]
        for (int i = 0; i < candidateCount; i++) {
            if (i % 2 == 0) {
                // Intersecting
                minX[i] = 1.0f; minY[i] = 1.0f; minZ[i] = 1.0f;
                maxX[i] = 3.0f; maxY[i] = 3.0f; maxZ[i] = 3.0f;
            } else {
                // Non-intersecting
                minX[i] = 100.0f; minY[i] = 100.0f; minZ[i] = 100.0f;
                maxX[i] = 102.0f; maxY[i] = 102.0f; maxZ[i] = 102.0f;
            }
        }

        final int hits = AgcSimdCollisionKernel.get().sweep64(
            0.0f, 0.0f, 0.0f,
            2.0f, 2.0f, 2.0f,
            minX, minY, minZ,
            maxX, maxY, maxZ,
            candidateCount,
            hitIndices
        );

        assertEquals(32, hits, "Exactly 32 even index candidates should intersect");
        for (int h = 0; h < hits; h++) {
            assertEquals(0, hitIndices[h] % 2, "Hit index must be even");
        }

        final AgcSimdCollisionKernel.CollisionMetrics metrics = AgcSimdCollisionKernel.get().metrics();
        assertEquals(1, metrics.totalCollisionQueries());
        assertEquals(64, metrics.totalBoxesTested());
        assertEquals(32, metrics.totalHitsDetected());
    }

    @Test
    void testTouchingFacesDoNotCollideForVanillaParity() {
        // In vanilla Minecraft, adjacent boxes that touch exactly at the face do NOT collide
        final float[] minX = new float[]{ 2.0f }; // touches target max X at 2.0f
        final float[] minY = new float[]{ 0.0f };
        final float[] minZ = new float[]{ 0.0f };
        final float[] maxX = new float[]{ 4.0f };
        final float[] maxY = new float[]{ 2.0f };
        final float[] maxZ = new float[]{ 2.0f };
        final int[] hitIndices = new int[1];

        final int hits = AgcSimdCollisionKernel.get().sweep64(
            0.0f, 0.0f, 0.0f,
            2.0f, 2.0f, 2.0f,
            minX, minY, minZ,
            maxX, maxY, maxZ,
            1,
            hitIndices
        );

        assertEquals(0, hits, "Touching boundary face must not produce a collision hit (vanilla parity)");
    }

    @Test
    void testSweepDoubleHitsDetection() {
        final int count = 16;
        final double[] minX = new double[count];
        final double[] minY = new double[count];
        final double[] minZ = new double[count];
        final double[] maxX = new double[count];
        final double[] maxY = new double[count];
        final double[] maxZ = new double[count];
        final int[] hitIndices = new int[count];

        for (int i = 0; i < count; i++) {
            if (i % 2 == 0) {
                // Intersecting
                minX[i] = 1.0; minY[i] = 1.0; minZ[i] = 1.0;
                maxX[i] = 3.0; maxY[i] = 3.0; maxZ[i] = 3.0;
            } else {
                // Non-intersecting
                minX[i] = 100.0; minY[i] = 100.0; minZ[i] = 100.0;
                maxX[i] = 102.0; maxY[i] = 102.0; maxZ[i] = 102.0;
            }
        }

        final int hits = AgcSimdCollisionKernel.get().sweepDouble(
            0.0, 0.0, 0.0,
            2.0, 2.0, 2.0,
            minX, minY, minZ,
            maxX, maxY, maxZ,
            count,
            hitIndices
        );

        assertEquals(8, hits, "Exactly 8 candidates should collide in double-precision SIMD sweep");
        for (int h = 0; h < hits; h++) {
            assertEquals(0, hitIndices[h] % 2, "Hit index must be even");
        }
    }

    @Test
    void testMultiPartBoxesSweep() {
        // Simulate complex multipart entity (e.g. EnderDragon 8 subparts)
        final net.minecraft.world.phys.AABB[] dragonParts = new net.minecraft.world.phys.AABB[]{
            new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1),       // head (intersects target)
            new net.minecraft.world.phys.AABB(1, 0, 0, 4, 3, 3),       // neck (intersects target)
            new net.minecraft.world.phys.AABB(3, 0, 0, 8, 3, 3),       // body (non-intersecting with [0,0,0 -> 2,2,2])
            new net.minecraft.world.phys.AABB(8, 0, 0, 10, 2, 2),      // tail1
            new net.minecraft.world.phys.AABB(10, 0, 0, 12, 2, 2),     // tail2
            new net.minecraft.world.phys.AABB(12, 0, 0, 14, 2, 2),     // tail3
            new net.minecraft.world.phys.AABB(2, 0, 3, 6, 2, 5),       // wing1
            new net.minecraft.world.phys.AABB(2, 0, -5, 6, 2, -3)      // wing2
        };
        final int[] hitIndices = new int[8];

        final int hits = AgcSimdCollisionKernel.get().sweepMultiPartBoxes(
            0.0, 0.0, 0.0, 2.0, 2.0, 2.0,
            dragonParts, dragonParts.length, hitIndices
        );

        // Head [0..1] and Neck [1..4] intersect with [0..2]
        assertEquals(2, hits, "EnderDragon head and neck should intersect target box");
        assertEquals(0, hitIndices[0]);
        assertEquals(1, hitIndices[1]);
    }

    @Test
    void testMultiPartNaNAndInfParity() {
        // IEEE 754: NaNs and Infinities must evaluate strictly false without crash
        assertFalse(AgcSimdCollisionKernel.testAABB(
            Double.NaN, 0, 0, 2, 2, 2,
            1, 1, 1, 3, 3, 3
        ));
        assertFalse(AgcSimdCollisionKernel.testAABB(
            0, 0, 0, Double.POSITIVE_INFINITY, 2, 2,
            5, 5, 5, 6, 6, 6
        ));
    }
}
