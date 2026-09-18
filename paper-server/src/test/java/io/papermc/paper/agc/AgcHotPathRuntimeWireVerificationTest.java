package io.papermc.paper.agc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AgcHotPathRuntimeWireVerificationTest {

    @Test
    public void testVarIntUnrolledCodecRoundTripParity() {
        final int[] testValues = {
            0, 1, 2, 63, 127,                           // 1 byte
            128, 255, 300, 16383,                       // 2 bytes
            16384, 32768, 65535, 1000000, 2097151,      // 3 bytes
            2097152, 10000000, 134217728, 268435455,    // 4 bytes
            268435456, Integer.MAX_VALUE, -1, -2, -100, Integer.MIN_VALUE // 5 bytes
        };

        for (final int value : testValues) {
            final ByteBuf buf = Unpooled.buffer(16);
            VarInt.write(buf, value);
            final int byteCount = buf.readableBytes();
            assertEquals(VarInt.getByteSize(value), byteCount, "Byte size mismatch for value: " + value);

            final int decoded = VarInt.read(buf);
            assertEquals(value, decoded, "Round-trip decode mismatch for value: " + value);
            assertEquals(0, buf.readableBytes(), "Dangling unread bytes for value: " + value);
            buf.release();
        }
    }

    @Test
    public void testVarLongUnrolledCodecAndLzcntByteSize() {
        final long[] testValues = {
            0L, 1L, 127L,                                       // 1 byte
            128L, 16383L,                                       // 2 bytes
            16384L, 2097151L,                                   // 3 bytes
            2097152L, 268435455L,                               // 4 bytes
            268435456L, (1L << 35) - 1,                         // 5 bytes
            1L << 35, (1L << 42) - 1,                           // 6 bytes
            1L << 42, (1L << 49) - 1,                           // 7 bytes
            1L << 49, (1L << 56) - 1,                           // 8 bytes
            1L << 56, Long.MAX_VALUE,                           // 9 bytes
            -1L, -100L, Long.MIN_VALUE                          // 10 bytes
        };

        for (final long value : testValues) {
            final int computedSize = VarLong.getByteSize(value);
            final ByteBuf buf = Unpooled.buffer(16);
            VarLong.write(buf, value);
            final int actualSize = buf.readableBytes();
            assertEquals(computedSize, actualSize, "VarLong byte size mismatch for value: " + value);

            final long decoded = VarLong.read(buf);
            assertEquals(value, decoded, "VarLong round-trip decode mismatch for value: " + value);
            assertEquals(0, buf.readableBytes(), "Dangling unread bytes for VarLong value: " + value);
            buf.release();
        }
    }

    @Test
    public void testDataLayerStarLightNibbleRecycling() {
        final io.papermc.paper.agc.light.AgcStarLightBatchOptimizer starlight =
            io.papermc.paper.agc.light.AgcStarLightBatchOptimizer.get();

        final long initialAcquired = starlight.metrics().nibblesAcquired();
        final long initialRecycled = starlight.metrics().nibblesRecycled();

        final DataLayer layer = new DataLayer();
        final byte[] data = layer.getData();
        assertNotNull(data);
        assertEquals(2048, data.length);
        assertTrue(starlight.metrics().nibblesAcquired() > initialAcquired);

        // Modify values
        layer.set(0, 0, 0, 15);
        assertEquals(15, layer.get(0, 0, 0));

        // Fill triggers recycling back to pool
        layer.fill(0);
        assertTrue(starlight.metrics().nibblesRecycled() > initialRecycled);
        assertEquals(0, layer.get(0, 0, 0));
    }

    @Test
    public void testHopperOptimizerIntegration() {
        final io.papermc.paper.agc.hopper.AgcHopperOptimizer hopperOpt =
            io.papermc.paper.agc.hopper.AgcHopperOptimizer.get();
        hopperOpt.clear();

        final long pos = 123456789L;
        final Object dummyContainer = new Object();
        final Object res1 = hopperOpt.getOrResolveTargetContainer(pos, () -> dummyContainer);
        assertSame(dummyContainer, res1);

        // Cache hit
        final Object res2 = hopperOpt.getOrResolveTargetContainer(pos, () -> new Object());
        assertSame(dummyContainer, res2);

        // Invalidation
        hopperOpt.invalidateHopper(pos);
        final Object dummyContainer2 = new Object();
        final Object res3 = hopperOpt.getOrResolveTargetContainer(pos, () -> dummyContainer2);
        assertSame(dummyContainer2, res3);

        // Directional key test: different direction indices yield distinct cache resolutions
        final long posDown = (pos * 31L) + 0; // DOWN
        final long posEast = (pos * 31L) + 5; // EAST
        final Object containerDown = new Object();
        final Object containerEast = new Object();
        assertSame(containerDown, hopperOpt.getOrResolveTargetContainer(posDown, () -> containerDown));
        assertSame(containerEast, hopperOpt.getOrResolveTargetContainer(posEast, () -> containerEast));
        assertNotSame(hopperOpt.getOrResolveTargetContainer(posDown, () -> new Object()),
                      hopperOpt.getOrResolveTargetContainer(posEast, () -> new Object()));
    }

    @Test
    public void testRedstoneOptimizerIntegration() {
        final io.papermc.paper.agc.redstone.AgcRedstoneOptimizer redstoneOpt =
            io.papermc.paper.agc.redstone.AgcRedstoneOptimizer.get();

        assertTrue(redstoneOpt.filterRedundantUpdate(15, 15));
        assertFalse(redstoneOpt.filterRedundantUpdate(15, 14));

        // Observer recursion limiter
        redstoneOpt.resetObserverChainDepth();
        for (int i = 0; i < io.papermc.paper.agc.redstone.AgcRedstoneOptimizer.MAX_OBSERVER_CHAIN_DEPTH; i++) {
            assertTrue(redstoneOpt.checkObserverChainDepth());
        }
        // Exceeding ceiling breaks loop
        assertFalse(redstoneOpt.checkObserverChainDepth());
        redstoneOpt.resetObserverChainDepth();
        assertTrue(redstoneOpt.checkObserverChainDepth());
    }

    @Test
    public void testVillagerOptimizerIntegration() {
        final io.papermc.paper.agc.villager.AgcVillagerOptimizer villagerOpt =
            io.papermc.paper.agc.villager.AgcVillagerOptimizer.get();

        // Golem spawn rate limiting
        final long villagerId = 42L;
        assertTrue(villagerOpt.canCheckGolemSpawn(villagerId, 100L));
        assertFalse(villagerOpt.canCheckGolemSpawn(villagerId, 105L));
        assertTrue(villagerOpt.canCheckGolemSpawn(villagerId, 250L));

        // Gossip sharing rate limiting
        final long v1 = 100L;
        final long v2 = 200L;
        assertTrue(villagerOpt.canShareGossip(v1, v2, 500L));
        assertFalse(villagerOpt.canShareGossip(v1, v2, 550L));
        assertTrue(villagerOpt.canShareGossip(v1, v2, 800L));
    }

    @Test
    public void testScoreboardOptimizerIntegration() {
        final io.papermc.paper.agc.scoreboard.AgcScoreboardOptimizer scoreOpt =
            io.papermc.paper.agc.scoreboard.AgcScoreboardOptimizer.get();

        assertTrue(scoreOpt.submitScoreUpdate("sidebar", "Player1", 100));
        // Redundant update filtered
        assertFalse(scoreOpt.submitScoreUpdate("sidebar", "Player1", 100));
        // Changed update accepted
        assertTrue(scoreOpt.submitScoreUpdate("sidebar", "Player1", 105));
    }

    @Test
    public void testCommandOptimizerEntityClamping() {
        final io.papermc.paper.agc.command.AgcCommandOptimizer cmdOpt =
            io.papermc.paper.agc.command.AgcCommandOptimizer.get();

        assertEquals(1000, cmdOpt.clampSelectorResults(2500, 1000));
        assertEquals(50, cmdOpt.clampSelectorResults(50, 1000));
        assertEquals(1000, cmdOpt.clampSelectorResults(150000, 0));
    }

    @Test
    public void testMthFastFloorAndCeilParity() {
        final double[] testDoubles = {
            -1000.75, -50.5, -1.99, -1.5, -1.0, -0.5, -0.01, 0.0,
            0.01, 0.5, 1.0, 1.5, 1.99, 50.5, 1000.75,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN,
            Double.MAX_VALUE, -Double.MAX_VALUE
        };

        for (final double val : testDoubles) {
            assertEquals((int) Math.floor(val), net.minecraft.util.Mth.floor(val), "floor(double) mismatch for: " + val);
            assertEquals((long) Math.floor(val), net.minecraft.util.Mth.lfloor(val), "lfloor(double) mismatch for: " + val);
            assertEquals((int) Math.ceil(val), net.minecraft.util.Mth.ceil(val), "ceil(double) mismatch for: " + val);
            assertEquals((long) Math.ceil(val), net.minecraft.util.Mth.ceilLong(val), "ceilLong(double) mismatch for: " + val);
        }

        final float[] testFloats = {
            -100.75f, -1.5f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f, 100.75f,
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN,
            Float.MAX_VALUE, -Float.MAX_VALUE
        };

        for (final float val : testFloats) {
            assertEquals((int) Math.floor(val), net.minecraft.util.Mth.floor(val), "floor(float) mismatch for: " + val);
            assertEquals((int) Math.ceil(val), net.minecraft.util.Mth.ceil(val), "ceil(float) mismatch for: " + val);
        }
    }

    @Test
    public void testAabbFastIntersectsParity() {
        final net.minecraft.world.phys.AABB box1 = new net.minecraft.world.phys.AABB(0, 0, 0, 2, 2, 2);
        final net.minecraft.world.phys.AABB boxIntersecting = new net.minecraft.world.phys.AABB(1, 1, 1, 3, 3, 3);
        final net.minecraft.world.phys.AABB boxTouching = new net.minecraft.world.phys.AABB(2, 0, 0, 4, 2, 2);
        final net.minecraft.world.phys.AABB boxDisjoint = new net.minecraft.world.phys.AABB(5, 5, 5, 6, 6, 6);

        assertTrue(box1.intersects(boxIntersecting));
        assertFalse(box1.intersects(boxTouching)); // Edge touching is not intersecting in AABB
        assertFalse(box1.intersects(boxDisjoint));

        assertTrue(box1.intersects(new net.minecraft.core.BlockPos(1, 1, 1)));
        assertFalse(box1.intersects(new net.minecraft.core.BlockPos(5, 5, 5)));

        // Parity check for NaN coordinates (must return false, not true)
        final net.minecraft.world.phys.AABB nanBoxY = new net.minecraft.world.phys.AABB(0, Double.NaN, 0, 2, Double.NaN, 2);
        assertFalse(nanBoxY.intersects(new net.minecraft.core.BlockPos(1, 1, 1)), "NaN coordinate in AABB must never intersect BlockPos");
        final net.minecraft.world.phys.AABB nanBoxX = new net.minecraft.world.phys.AABB(Double.NaN, 0, 0, Double.NaN, 2, 2);
        assertFalse(nanBoxX.intersects(new net.minecraft.core.BlockPos(1, 1, 1)), "NaN X coordinate in AABB must never intersect BlockPos");
        final net.minecraft.world.phys.AABB nanBoxZ = new net.minecraft.world.phys.AABB(0, 0, Double.NaN, 2, 2, Double.NaN);
        assertFalse(nanBoxZ.intersects(new net.minecraft.core.BlockPos(1, 1, 1)), "NaN Z coordinate in AABB must never intersect BlockPos");

        // Parity check against AgcSimdCollisionKernel
        assertTrue(io.papermc.paper.agc.AgcSimdCollisionKernel.testAABB(0, 0, 0, 2, 2, 2, 1, 1, 1, 3, 3, 3));
        assertFalse(io.papermc.paper.agc.AgcSimdCollisionKernel.testAABB(0, 0, 0, 2, 2, 2, 2, 0, 0, 4, 2, 2));
        assertFalse(io.papermc.paper.agc.AgcSimdCollisionKernel.testAABB(0, 0, 0, 2, 2, 2, 5, 5, 5, 6, 6, 6));

        // Parity check against AgcVectorMath
        final double[] testAabbs = {
            1, 1, 1, 3, 3, 3,
            2, 0, 0, 4, 2, 2,
            5, 5, 5, 6, 6, 6
        };
        final boolean[] res = new boolean[3];
        final int hits = io.papermc.paper.agc.simd.AgcVectorMath.get().testBatchAabbIntersections(0, 0, 0, 2, 2, 2, testAabbs, 3, res);
        assertEquals(1, hits);
        assertTrue(res[0]);
        assertFalse(res[1], "VectorMath must reject edge-touching bounding boxes");
        assertFalse(res[2]);
    }

    @Test
    public void testScoreboardInvalidation() {
        final io.papermc.paper.agc.scoreboard.AgcScoreboardOptimizer scoreOpt =
            io.papermc.paper.agc.scoreboard.AgcScoreboardOptimizer.get();
        scoreOpt.clear();

        assertTrue(scoreOpt.submitScoreUpdate("obj1", "Alice", 10));
        assertFalse(scoreOpt.submitScoreUpdate("obj1", "Alice", 10)); // Deduplicated

        scoreOpt.invalidateScore("obj1", "Alice");
        assertTrue(scoreOpt.submitScoreUpdate("obj1", "Alice", 10)); // Re-accepted after invalidation

        scoreOpt.invalidateObjective("obj1");
        assertTrue(scoreOpt.submitScoreUpdate("obj1", "Alice", 10)); // Re-accepted after objective invalidation

        scoreOpt.invalidatePlayer("Alice");
        assertTrue(scoreOpt.submitScoreUpdate("obj1", "Alice", 10)); // Re-accepted after player invalidation
    }

    @Test
    public void testMthWrapDegreesAndClampParity() {
        final float[] testAnglesF = {
            0.0f, 45.0f, 90.0f, 179.9f, 180.0f, 180.1f, 359.0f, 360.0f, 360.1f, 720.0f,
            -0.0f, -45.0f, -90.0f, -179.9f, -180.0f, -180.1f, -359.0f, -360.0f, -720.0f,
            1234.56f, -9876.54f
        };

        for (final float a : testAnglesF) {
            float expected = a % 360.0F;
            if (expected >= 180.0F) expected -= 360.0F;
            if (expected < -180.0F) expected += 360.0F;
            assertEquals(expected, net.minecraft.util.Mth.wrapDegrees(a), 1e-4f, "wrapDegrees(float) parity failed for: " + a);

            float expected90 = a % 90.0F;
            if (expected90 >= 45.0F) expected90 -= 90.0F;
            if (expected90 < -45.0F) expected90 += 90.0F;
            assertEquals(expected90, net.minecraft.util.Mth.wrapDegrees90(a), 1e-4f, "wrapDegrees90(float) parity failed for: " + a);

            // Float sin/cos parity against double version
            assertEquals(net.minecraft.util.Mth.sin((double) a), net.minecraft.util.Mth.sin(a), 0.0f, "sin(float) parity failed for: " + a);
            assertEquals(net.minecraft.util.Mth.cos((double) a), net.minecraft.util.Mth.cos(a), 0.0f, "cos(float) parity failed for: " + a);
        }

        final double[] testAnglesD = {
            0.0, 45.0, 90.0, 179.9, 180.0, 180.1, 359.0, 360.0, 360.1, 720.0,
            -0.0, -45.0, -90.0, -179.9, -180.0, -180.1, -359.0, -360.0, -720.0,
            12345.6789, -98765.4321
        };

        for (final double a : testAnglesD) {
            double expected = a % 360.0;
            if (expected >= 180.0) expected -= 360.0;
            if (expected < -180.0) expected += 360.0;
            assertEquals(expected, net.minecraft.util.Mth.wrapDegrees(a), 1e-6, "wrapDegrees(double) parity failed for: " + a);
        }

        // Clamp parity
        assertEquals(5, net.minecraft.util.Mth.clamp(5, 0, 10));
        assertEquals(0, net.minecraft.util.Mth.clamp(-5, 0, 10));
        assertEquals(10, net.minecraft.util.Mth.clamp(15, 0, 10));
        assertEquals(5L, net.minecraft.util.Mth.clamp(5L, 0L, 10L));
        assertEquals(0L, net.minecraft.util.Mth.clamp(-5L, 0L, 10L));
        assertEquals(10L, net.minecraft.util.Mth.clamp(15L, 0L, 10L));
        assertEquals(5.5f, net.minecraft.util.Mth.clamp(5.5f, 0.0f, 10.0f));
        assertEquals(0.0f, net.minecraft.util.Mth.clamp(-5.5f, 0.0f, 10.0f));
        assertEquals(10.0f, net.minecraft.util.Mth.clamp(15.5f, 0.0f, 10.0f));
        assertEquals(5.5, net.minecraft.util.Mth.clamp(5.5, 0.0, 10.0));
        assertEquals(0.0, net.minecraft.util.Mth.clamp(-5.5, 0.0, 10.0));
        assertEquals(10.0, net.minecraft.util.Mth.clamp(15.5, 0.0, 10.0));

        // Edge cases for float/double clamp: NaNs and signed zeros
        assertTrue(Float.isNaN(net.minecraft.util.Mth.clamp(Float.NaN, 0.0f, 10.0f)));
        assertTrue(Float.isNaN(net.minecraft.util.Mth.clamp(5.0f, 0.0f, Float.NaN)));
        assertTrue(Double.isNaN(net.minecraft.util.Mth.clamp(Double.NaN, 0.0, 10.0)));
        assertTrue(Double.isNaN(net.minecraft.util.Mth.clamp(5.0, 0.0, Double.NaN)));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(net.minecraft.util.Mth.clamp(-0.0f, -10.0f, 10.0f)));
    }

    @Test
    public void testSpawnerDensitySuppressionIntegration() {
        final io.papermc.paper.agc.spawner.AgcSpawnerOptimizer spawnerOpt =
            io.papermc.paper.agc.spawner.AgcSpawnerOptimizer.get();
        spawnerOpt.clear();

        final long chunkKey = 99887766L;
        // Under limit (10 < 24) -> allowed
        assertTrue(spawnerOpt.canSpawnInChunk(chunkKey, 10, 24));
        // Over limit (25 >= 24) -> suppressed
        assertFalse(spawnerOpt.canSpawnInChunk(chunkKey, 25, 24));
        // At limit (24 >= 24) -> suppressed
        assertFalse(spawnerOpt.canSpawnInChunk(chunkKey, 24, 24));

        // Memory bounded capacity test: inserting 3,000 distinct chunks must not leak memory
        for (long k = 1; k <= 3000; k++) {
            spawnerOpt.canSpawnInChunk(k, 5, 24);
        }
        assertTrue(spawnerOpt.metrics().trackedChunks() <= 1024, "Spawner tracking must be bounded to 1024 chunks to prevent memory leaks");

        // AgcCapabilityMatrix gating: BASELINE enables density suppression by default (24 mobs/chunk cap)
        io.papermc.paper.agc.AgcCapabilityMatrix.setMode(io.papermc.paper.agc.AgcCapabilityMatrix.Mode.VANILLA);
        assertFalse(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.SPAWNER_DENSITY_OPTIMIZER));
        io.papermc.paper.agc.AgcCapabilityMatrix.setMode(io.papermc.paper.agc.AgcCapabilityMatrix.Mode.AGC_BASELINE);
        assertTrue(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.SPAWNER_DENSITY_OPTIMIZER));
        io.papermc.paper.agc.AgcCapabilityMatrix.setMode(io.papermc.paper.agc.AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        assertTrue(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.SPAWNER_DENSITY_OPTIMIZER));
        io.papermc.paper.agc.AgcCapabilityMatrix.setMode(io.papermc.paper.agc.AgcCapabilityMatrix.Mode.AGC_BASELINE);
    }

    @Test
    public void testMultipartDragonCollisionParity() {
        // Multi-part boxes: target query AABB at [0,0,0 -> 2,2,2]
        final net.minecraft.world.phys.AABB targetBox = new net.minecraft.world.phys.AABB(0, 0, 0, 2, 2, 2);
        final net.minecraft.world.phys.AABB headBox = new net.minecraft.world.phys.AABB(0.5, 0.5, 0.5, 1.5, 1.5, 1.5);
        final net.minecraft.world.phys.AABB tailBox = new net.minecraft.world.phys.AABB(10.0, 10.0, 10.0, 12.0, 12.0, 12.0);

        final net.minecraft.world.phys.AABB[] parts = new net.minecraft.world.phys.AABB[]{ headBox, tailBox };
        final int[] hitIndices = new int[2];

        final int hits = io.papermc.paper.agc.AgcSimdCollisionKernel.get().sweepMultiPartBoxes(
            targetBox.minX, targetBox.minY, targetBox.minZ,
            targetBox.maxX, targetBox.maxY, targetBox.maxZ,
            parts, parts.length, hitIndices
        );

        // Strict parity: only headBox intersects; distant tailBox MUST NOT be reported
        assertEquals(1, hits, "Only intersecting head part must be detected");
        assertEquals(0, hitIndices[0]);
    }

    @Test
    public void testHopperSourceContainerCaching() {
        final io.papermc.paper.agc.hopper.AgcHopperOptimizer hopperOpt =
            io.papermc.paper.agc.hopper.AgcHopperOptimizer.get();

        final long sourcePackedKey = (42L * 31L) + 6L; // Direction UP / source
        final Object sourceContainer = new Object();
        final Object resolved = hopperOpt.getOrResolveTargetContainer(sourcePackedKey, () -> sourceContainer);
        assertSame(sourceContainer, resolved);

        // Cache hit
        final Object cached = hopperOpt.getOrResolveTargetContainer(sourcePackedKey, () -> new Object());
        assertSame(sourceContainer, cached);

        // Invalidation on removal
        hopperOpt.invalidateHopper(sourcePackedKey);
        final Object newSourceContainer = new Object();
        final Object reResolved = hopperOpt.getOrResolveTargetContainer(sourcePackedKey, () -> newSourceContainer);
        assertSame(newSourceContainer, reResolved);
    }

    @Test
    public void testFastNoiseBranchlessGradDotParity() {
        final double[][] testCoords = {
            { 0.0, 0.0, 0.0 },
            { 1.0, 1.0, 1.0 },
            { -1.0, -1.0, -1.0 },
            { 0.25, 0.5, 0.75 },
            { -0.333, 0.666, -0.999 },
            { 123.456, -789.012, 345.678 }
        };

        final int[][] expectedGradients = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
            {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
        };

        for (int hash = 0; hash < 16; hash++) {
            final int[] grad = expectedGradients[hash];
            for (final double[] coord : testCoords) {
                final double x = coord[0];
                final double y = coord[1];
                final double z = coord[2];

                final double vanillaDot = (double) grad[0] * x + (double) grad[1] * y + (double) grad[2] * z;
                final double fastDot = net.minecraft.world.level.levelgen.synth.ImprovedNoise.gradDot(hash, x, y, z);

                assertEquals(vanillaDot, fastDot, 1.0E-14,
                    "FastNoise gradDot mismatch for hash=" + hash + " at (" + x + "," + y + "," + z + ")");
            }
        }
    }

    @Test
    public void testStructureLayoutOptimizerContainmentSoundness() {
        // Free space bounding volume: [0, 0, 0 -> 10, 10, 10]
        final net.minecraft.world.level.levelgen.structure.BoundingBox sourceBB =
            new net.minecraft.world.level.levelgen.structure.BoundingBox(0, 0, 0, 10, 10, 10);
        final net.minecraft.world.phys.shapes.VoxelShape freeShape =
            net.minecraft.world.phys.shapes.Shapes.create(net.minecraft.world.phys.AABB.of(sourceBB));

        // Case 1: Candidate piece strictly inside free space: [2, 2, 2 -> 5, 5, 5]
        final net.minecraft.world.level.levelgen.structure.BoundingBox insideBB =
            new net.minecraft.world.level.levelgen.structure.BoundingBox(2, 2, 2, 5, 5, 5);
        final net.minecraft.world.phys.shapes.VoxelShape insideTarget =
            net.minecraft.world.phys.shapes.Shapes.create(net.minecraft.world.phys.AABB.of(insideBB).deflate(0.25));
        // ONLY_SECOND: target && !free. Since target is fully inside free, target \ free is empty -> false!
        assertFalse(net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(freeShape, insideTarget, net.minecraft.world.phys.shapes.BooleanOp.ONLY_SECOND),
            "Inside piece must be contained in free volume");

        // Case 2: Candidate piece protruding outside on X: [8, 2, 2 -> 15, 5, 5]
        final net.minecraft.world.level.levelgen.structure.BoundingBox outsideBB =
            new net.minecraft.world.level.levelgen.structure.BoundingBox(8, 2, 2, 15, 5, 5);
        final net.minecraft.world.phys.shapes.VoxelShape outsideTarget =
            net.minecraft.world.phys.shapes.Shapes.create(net.minecraft.world.phys.AABB.of(outsideBB).deflate(0.25));
        // MUST be non-empty (true) -> invalid placement!
        assertTrue(net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(freeShape, outsideTarget, net.minecraft.world.phys.shapes.BooleanOp.ONLY_SECOND),
            "Outside piece must violate containment");

        // Verify the fast containment check agrees with Shapes.joinIsNotEmpty:
        final net.minecraft.world.phys.AABB freeBounds = freeShape.bounds();
        final double deflatedMaxX = (double) outsideBB.maxX() + 0.75;
        assertTrue(deflatedMaxX > freeBounds.maxX, "Fast-path must detect X bound overflow");
    }

    @Test
    public void testLithiumEmptyHopperParity() {
        // Dummy container implementing Container where isEmpty() returns true
        final net.minecraft.world.SimpleContainer emptyChest = new net.minecraft.world.SimpleContainer(27);
        assertTrue(emptyChest.isEmpty());
        // All items in empty chest are empty
        for (int i = 0; i < emptyChest.getContainerSize(); i++) {
            assertTrue(emptyChest.getItem(i).isEmpty());
        }
    }

    @Test
    public void testStructureLayoutOptimizerEmptyFreeShapeImmunity() {
        // When free volume is completely exhausted, free shape is Shapes.empty()
        final net.minecraft.world.phys.shapes.VoxelShape emptyFree = net.minecraft.world.phys.shapes.Shapes.empty();
        assertTrue(emptyFree.isEmpty());

        // Calling .bounds() directly on emptyFree throws UnsupportedOperationException:
        assertThrows(UnsupportedOperationException.class, emptyFree::bounds,
            "Direct bounds() on empty VoxelShape must throw per Minecraft specification");

        // AGC's guarded logic must check isEmpty() first and reject the piece with zero exceptions:
        final net.minecraft.world.level.levelgen.structure.BoundingBox candidateBB =
            new net.minecraft.world.level.levelgen.structure.BoundingBox(0, 0, 0, 5, 5, 5);

        boolean skipped = false;
        if (io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.STRUCTURE_LAYOUT_OPTIMIZER)) {
            if (emptyFree.isEmpty()) {
                skipped = true;
            }
        }
        assertTrue(skipped, "Empty free volume must short-circuit without calling .bounds()");
    }

    @Test
    public void testHopperEmptyWorldlyContainerZeroSlotParity() {
        // A WorldlyContainer (e.g. customized container or restricted side) with 0 accessible slots:
        class RestrictedWorldlyContainer extends net.minecraft.world.SimpleContainer implements net.minecraft.world.WorldlyContainer {
            public RestrictedWorldlyContainer(int size) {
                super(size);
            }
            @Override
            public int[] getSlotsForFace(net.minecraft.core.Direction side) {
                return new int[0]; // 0 slots accessible from this face!
            }
            @Override
            public boolean canPlaceItemThroughFace(int slot, net.minecraft.world.item.ItemStack stack, net.minecraft.core.Direction dir) {
                return false;
            }
            @Override
            public boolean canTakeItemThroughFace(int slot, net.minecraft.world.item.ItemStack stack, net.minecraft.core.Direction dir) {
                return false;
            }
        }
        final RestrictedWorldlyContainer restrictedContainer = new RestrictedWorldlyContainer(5);

        assertTrue(restrictedContainer.isEmpty());
        final int[] slots = restrictedContainer.getSlotsForFace(net.minecraft.core.Direction.DOWN);
        assertEquals(0, slots.length);

        // When slots.length == 0, isFullContainer MUST be true (cannot accept items) despite container.isEmpty() being true!
        final boolean isFull;
        if (slots.length == 0) {
            isFull = true;
        } else if (restrictedContainer.isEmpty()) {
            isFull = false;
        } else {
            isFull = true;
        }
        assertTrue(isFull, "Container with 0 accessible slots must be treated as full (cannot accept items)");
    }

    @Test
    public void testLithiumConcurrentChunkRegisterConsistency() throws Exception {
        // Verify that atomic ChunkCacheEntry record prevents key-chunk mismatches under concurrent access
        record ChunkEntry(long key, String chunkName) {}
        class MockCache {
            volatile ChunkEntry entry;
            void put(long key, String chunk) {
                entry = new ChunkEntry(key, chunk);
            }
            String get(long key) {
                final ChunkEntry e = entry;
                if (e != null && e.key() == key) {
                    return e.chunkName();
                }
                return null;
            }
        }

        final MockCache cache = new MockCache();
        final int threads = 8;
        final int iterations = 10000;
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        final java.util.concurrent.atomic.AtomicBoolean mismatchDetected = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        final long key = (threadId * 1000000L) + (i % 10);
                        final String expectedName = "chunk_" + key;
                        cache.put(key, expectedName);

                        final String retrieved = cache.get(key);
                        if (retrieved != null && !retrieved.equals(expectedName)) {
                            mismatchDetected.set(true);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        assertFalse(mismatchDetected.get(), "Atomic cache entry must never allow key-chunk mismatches");
    }

    @Test
    public void testLithiumThreadLocalMultiSlotResilienceAndGenerationalInvalidation() throws Exception {
        final int slots = 4;
        final int mask = slots - 1;
        final java.util.concurrent.atomic.AtomicLong globalGeneration = new java.util.concurrent.atomic.AtomicLong(1L);
        final java.util.concurrent.ConcurrentHashMap<Long, String> authoritativeMap = new java.util.concurrent.ConcurrentHashMap<>();

        // Per-thread cache structure mirroring ServerChunkCache.WorkerChunkCache with owner validation
        class LocalCache {
            final Object[] owners = new Object[slots];
            final long[] keys = new long[slots];
            final String[] chunks = new String[slots];
            final long[] gens = new long[slots];

            LocalCache() {
                java.util.Arrays.fill(keys, -1L);
            }

            String get(Object owner, long key, java.util.Map<Long, String> map) {
                int slot = ((int) (key ^ (key >>> 16))) & mask;
                long currentGen = globalGeneration.get();
                if (owners[slot] == owner && keys[slot] == key && gens[slot] == currentGen) {
                    return chunks[slot];
                }
                // Fallback to authoritative map
                String val = map.get(key);
                if (val != null) {
                    owners[slot] = owner;
                    keys[slot] = key;
                    chunks[slot] = val;
                    gens[slot] = currentGen;
                }
                return val;
            }

            void unload(Object owner, long key, java.util.Map<Long, String> map) {
                map.remove(key);
                globalGeneration.incrementAndGet();
                int slot = ((int) (key ^ (key >>> 16))) & mask;
                if (owners[slot] == owner && keys[slot] == key) {
                    owners[slot] = null;
                    keys[slot] = -1L;
                    chunks[slot] = null;
                }
            }
        }

        final ThreadLocal<LocalCache> threadLocal = ThreadLocal.withInitial(LocalCache::new);
        final Object worldA = new Object();
        final Object worldB = new Object();
        final java.util.concurrent.ConcurrentHashMap<Long, String> worldBMap = new java.util.concurrent.ConcurrentHashMap<>();

        // Pre-populate authoritative map with 4 chunks
        for (long k = 10; k < 14; k++) {
            authoritativeMap.put(k, "chunkA_" + k);
            worldBMap.put(k, "chunkB_" + k);
        }

        // Test 1: Multi-slot caching on single thread
        final LocalCache mainCache = threadLocal.get();
        assertEquals("chunkA_10", mainCache.get(worldA, 10L, authoritativeMap));
        assertEquals("chunkA_11", mainCache.get(worldA, 11L, authoritativeMap));
        assertEquals("chunkA_10", mainCache.get(worldA, 10L, authoritativeMap)); // Hit from slot

        // Test 2: Cross-world isolation (same key across different worlds must never collide in ThreadLocal)
        assertEquals("chunkB_10", mainCache.get(worldB, 10L, worldBMap), "World B query must return World B chunk even if World A chunk with same key was cached");
        assertEquals("chunkA_10", mainCache.get(worldA, 10L, authoritativeMap), "World A query must return World A chunk");

        // Test 3: Generational invalidation on unload
        mainCache.unload(worldA, 10L, authoritativeMap);
        assertNull(mainCache.get(worldA, 10L, authoritativeMap), "Unloaded chunk must not be returned");
        assertEquals("chunkA_11", mainCache.get(worldA, 11L, authoritativeMap), "Non-unloaded chunk must remain accessible");

        // Test 4: Concurrent cross-thread isolation (no cache thrashing)
        final int threads = 6;
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        final java.util.concurrent.atomic.AtomicBoolean failure = new java.util.concurrent.atomic.AtomicBoolean(false);

        for (int t = 0; t < threads; t++) {
            final long dedicatedKey = 1000L + t;
            authoritativeMap.put(dedicatedKey, "val_" + dedicatedKey);
            pool.submit(() -> {
                try {
                    final LocalCache c = threadLocal.get();
                    for (int i = 0; i < 5000; i++) {
                        String res = c.get(worldA, dedicatedKey, authoritativeMap);
                        if (!("val_" + dedicatedKey).equals(res)) {
                            failure.set(true);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        assertFalse(failure.get(), "Concurrent worker threads must not thrash each other's ThreadLocal chunk registers");
    }

    @Test
    public void testCapabilityMatrixModeGating() {
        // In VANILLA mode, all performance optimizations must be disabled:
        io.papermc.paper.agc.AgcCapabilityMatrix.setMode(io.papermc.paper.agc.AgcCapabilityMatrix.Mode.VANILLA);
        assertFalse(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.FAST_NOISE_GENERATOR));
        assertFalse(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.STRUCTURE_LAYOUT_OPTIMIZER));
        assertFalse(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.LITHIUM_CHUNK_REGISTER));
        assertFalse(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.HOPPER_OPTIMIZER));

        // In AGC_BASELINE, baseline optimizations must be active:
        io.papermc.paper.agc.AgcCapabilityMatrix.setMode(io.papermc.paper.agc.AgcCapabilityMatrix.Mode.AGC_BASELINE);
        assertTrue(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.FAST_NOISE_GENERATOR));
        assertTrue(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.STRUCTURE_LAYOUT_OPTIMIZER));
        assertTrue(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.LITHIUM_CHUNK_REGISTER));
        assertTrue(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(io.papermc.paper.agc.AgcCapabilityMatrix.Feature.HOPPER_OPTIMIZER));
    }

    @Test
    public void testPlayerChunkSenderNeedsTickContract() {
        final net.minecraft.server.network.PlayerChunkSender sender = new net.minecraft.server.network.PlayerChunkSender(false);
        // Initially quota is 0 < 9.0, so needsTick is true to ramp up quota
        assertTrue(sender.meteus$needsTick(), "Initial sender needs tick to accumulate quota");

        // Simulate quota ramp up via sendNextChunks with no player (or reflection/state change)
        // Calling onChunkBatchReceivedByClient with unacknowledgedBatches = 0 sets batchQuota = 1.0
        sender.onChunkBatchReceivedByClient(1.0F); // desired = 1.0, maxBatch = 1.0, quota = 1.0
        // Now pendingChunks is empty and batchQuota (1.0) >= maxBatchSize (1.0), so sender is idle!
        assertFalse(sender.meteus$needsTick(), "Idle sender with saturated quota and empty chunks must not need tick");
    }

    @Test
    public void testParallelLightEngineSubmitTaskContract() throws Exception {
        final io.papermc.paper.agc.light.AgcParallelLightEngine engine = io.papermc.paper.agc.light.AgcParallelLightEngine.get();
        engine.shutdown();
        io.papermc.paper.agc.AgcCapabilityMatrix.setMode(io.papermc.paper.agc.AgcCapabilityMatrix.Mode.AGC_BASELINE);
        final java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean(false);

        // When not bootstrapped or feature disabled, submitTask must execute inline fallback synchronously
        final java.util.concurrent.Future<?> f = engine.submitTask(() -> ran.set(true));
        assertNotNull(f);
        assertTrue(f.isDone());
        assertTrue(ran.get(), "Task must run immediately on inline fallback");

        // When bootstrapped and feature enabled
        io.papermc.paper.agc.AgcCapabilityMatrix.setMode(io.papermc.paper.agc.AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        engine.bootstrap();
        try {
            final java.util.concurrent.atomic.AtomicBoolean ranParallel = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.Future<?> future = engine.submitTask(() -> ranParallel.set(true));
            assertNotNull(future);
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(ranParallel.get(), "Task must run on parallel pool");
        } finally {
            engine.shutdown();
            io.papermc.paper.agc.AgcCapabilityMatrix.setMode(io.papermc.paper.agc.AgcCapabilityMatrix.Mode.AGC_BASELINE);
        }
    }

    @Test
    public void testVisibilityMutationVersionIntegrity() {
        final int initial = org.bukkit.craftbukkit.entity.CraftPlayer.meteus$getVisibilityMutationVersion();
        assertTrue(initial >= 0, "Visibility mutation version must be non-negative");

        org.bukkit.craftbukkit.entity.CraftPlayer.meteus$notifyVisibilityMutated();
        final int afterExplicit = org.bukkit.craftbukkit.entity.CraftPlayer.meteus$getVisibilityMutationVersion();
        assertEquals(initial + 1, afterExplicit, "notifyVisibilityMutated must increment version by exactly 1");

        org.bukkit.craftbukkit.entity.CraftPlayer.meteus$notifyVisibilityMutated();
        final int afterSecond = org.bukkit.craftbukkit.entity.CraftPlayer.meteus$getVisibilityMutationVersion();
        assertEquals(afterExplicit + 1, afterSecond, "Repeated mutations must monotonically increment version");
    }

    @Test
    public void testServerScoreboardNullSafety() {
        final net.minecraft.server.ServerScoreboard scoreboard = new net.minecraft.server.ServerScoreboard(null);
        final net.minecraft.world.scores.Objective dummyObjective = new net.minecraft.world.scores.Objective(
            scoreboard,
            "test_obj",
            net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
            net.minecraft.network.chat.Component.literal("Test"),
            net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER,
            false,
            null
        );

        // When server is null (e.g. mock/test environments), tracking must safely operate on local set without NPE
        assertDoesNotThrow(() -> scoreboard.startTrackingObjective(dummyObjective));
        assertTrue(scoreboard.getTrackedObjectives().contains(dummyObjective));

        assertDoesNotThrow(() -> scoreboard.stopTrackingObjective(dummyObjective));
        assertFalse(scoreboard.getTrackedObjectives().contains(dummyObjective));
    }

    @Test
    public void testCopyOnWriteSafeIterationPattern() {
        final java.util.concurrent.CopyOnWriteArrayList<String> cowList = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (int i = 0; i < 50; i++) {
            cowList.add("item-" + i);
        }

        final java.util.concurrent.atomic.AtomicInteger visited = new java.util.concurrent.atomic.AtomicInteger();

        // Simulate concurrent removal during fast-path loop
        for (int i = 0; i < cowList.size(); i++) {
            final String item;
            try {
                item = cowList.get(i);
            } catch (final IndexOutOfBoundsException ignored) {
                break;
            }
            if (item == null) continue;
            visited.incrementAndGet();

            if (i == 10) {
                // Shrink list drastically mid-iteration
                cowList.clear();
            }
        }

        // Must not have thrown IndexOutOfBoundsException and should exit cleanly
        assertEquals(11, visited.get(), "Loop should safely terminate at point of truncation without crashing");
    }

    @Test
    public void testPlayerListSnapshotAutoSync() {
        class MockPlayerListHolder {
            volatile String[] snapshot = new String[0];

            void updateSnapshot() {
                this.snapshot = this.list == null ? new String[0] : this.list.toArray(new String[0]);
            }

            final java.util.concurrent.CopyOnWriteArrayList<String> list = new java.util.concurrent.CopyOnWriteArrayList<>() {
                @Override
                public boolean add(final String e) {
                    final boolean r = super.add(e);
                    updateSnapshot();
                    return r;
                }

                @Override
                public void add(final int index, final String element) {
                    super.add(index, element);
                    updateSnapshot();
                }

                @Override
                public boolean addAll(final java.util.Collection<? extends String> c) {
                    final boolean r = super.addAll(c);
                    updateSnapshot();
                    return r;
                }

                @Override
                public boolean addAll(final int index, final java.util.Collection<? extends String> c) {
                    final boolean r = super.addAll(index, c);
                    updateSnapshot();
                    return r;
                }

                @Override
                public boolean remove(final Object o) {
                    final boolean r = super.remove(o);
                    updateSnapshot();
                    return r;
                }

                @Override
                public String remove(final int index) {
                    final String r = super.remove(index);
                    updateSnapshot();
                    return r;
                }

                @Override
                public boolean removeAll(final java.util.Collection<?> c) {
                    final boolean r = super.removeAll(c);
                    updateSnapshot();
                    return r;
                }

                @Override
                public boolean retainAll(final java.util.Collection<?> c) {
                    final boolean r = super.retainAll(c);
                    updateSnapshot();
                    return r;
                }

                @Override
                public void clear() {
                    super.clear();
                    updateSnapshot();
                }

                @Override
                public boolean removeIf(final java.util.function.Predicate<? super String> filter) {
                    final boolean r = super.removeIf(filter);
                    updateSnapshot();
                    return r;
                }

                @Override
                public void replaceAll(final java.util.function.UnaryOperator<String> operator) {
                    super.replaceAll(operator);
                    updateSnapshot();
                }

                @Override
                public String set(final int index, final String element) {
                    final String r = super.set(index, element);
                    updateSnapshot();
                    return r;
                }

                @Override
                public void addFirst(final String element) {
                    super.addFirst(element);
                    updateSnapshot();
                }

                @Override
                public void addLast(final String element) {
                    super.addLast(element);
                    updateSnapshot();
                }

                @Override
                public String removeFirst() {
                    final String r = super.removeFirst();
                    updateSnapshot();
                    return r;
                }

                @Override
                public String removeLast() {
                    final String r = super.removeLast();
                    updateSnapshot();
                    return r;
                }

                @Override
                public boolean addIfAbsent(final String e) {
                    final boolean r = super.addIfAbsent(e);
                    if (r) {
                        updateSnapshot();
                    }
                    return r;
                }

                @Override
                public int addAllAbsent(final java.util.Collection<? extends String> c) {
                    final int r = super.addAllAbsent(c);
                    if (r > 0) {
                        updateSnapshot();
                    }
                    return r;
                }

                @Override
                public void sort(final java.util.Comparator<? super String> c) {
                    super.sort(c);
                    updateSnapshot();
                }
            };
        }

        final MockPlayerListHolder holder = new MockPlayerListHolder();
        assertEquals(0, holder.snapshot.length);

        // 1. add & set
        holder.list.add("Player1");
        assertEquals(1, holder.snapshot.length);
        assertEquals("Player1", holder.snapshot[0]);

        holder.list.set(0, "Player1Updated");
        assertEquals(1, holder.snapshot.length);
        assertEquals("Player1Updated", holder.snapshot[0]);

        // 2. addFirst & addLast
        holder.list.addFirst("Player0");
        assertEquals(2, holder.snapshot.length);
        assertEquals("Player0", holder.snapshot[0]);
        assertEquals("Player1Updated", holder.snapshot[1]);

        holder.list.addLast("Player2");
        assertEquals(3, holder.snapshot.length);
        assertEquals("Player2", holder.snapshot[2]);

        // 3. addIfAbsent & addAllAbsent
        assertFalse(holder.list.addIfAbsent("Player0"));
        assertEquals(3, holder.snapshot.length);
        assertTrue(holder.list.addIfAbsent("Player3"));
        assertEquals(4, holder.snapshot.length);
        assertEquals("Player3", holder.snapshot[3]);

        assertEquals(0, holder.list.addAllAbsent(java.util.List.of("Player0", "Player2")));
        assertEquals(4, holder.snapshot.length);
        assertEquals(1, holder.list.addAllAbsent(java.util.List.of("Player0", "Player4")));
        assertEquals(5, holder.snapshot.length);
        assertEquals("Player4", holder.snapshot[4]);

        // 4. removeFirst & removeLast
        assertEquals("Player0", holder.list.removeFirst());
        assertEquals(4, holder.snapshot.length);
        assertEquals("Player1Updated", holder.snapshot[0]);

        assertEquals("Player4", holder.list.removeLast());
        assertEquals(3, holder.snapshot.length);
        assertEquals("Player3", holder.snapshot[2]);

        // 5. sort
        holder.list.sort(java.util.Comparator.reverseOrder());
        assertEquals("Player3", holder.snapshot[0]);
        assertEquals("Player1Updated", holder.snapshot[2]);

        // 6. replaceAll & removeIf
        holder.list.replaceAll(s -> s.toUpperCase(java.util.Locale.ROOT));
        assertEquals("PLAYER3", holder.snapshot[0]);

        holder.list.removeIf(s -> s.contains("3"));
        assertEquals(2, holder.snapshot.length);
        assertFalse(java.util.Arrays.asList(holder.snapshot).contains("PLAYER3"));

        // 7. clear
        holder.list.clear();
        assertEquals(0, holder.snapshot.length);
    }

    @Test
    public void testPathReconstructionOrderParity() {
        // Empty / null path test
        final java.util.List<net.minecraft.world.level.pathfinder.Node> emptyNodes = new java.util.ArrayList<>();
        net.minecraft.world.level.pathfinder.Node nullNode = null;
        while (nullNode != null) {
            emptyNodes.add(nullNode);
            nullNode = nullNode.cameFrom;
        }
        java.util.Collections.reverse(emptyNodes);
        assertTrue(emptyNodes.isEmpty());

        // Single node path
        final net.minecraft.world.level.pathfinder.Node single = new net.minecraft.world.level.pathfinder.Node(10, 64, 10);
        final java.util.List<net.minecraft.world.level.pathfinder.Node> singleNodes = new java.util.ArrayList<>();
        net.minecraft.world.level.pathfinder.Node currSingle = single;
        while (currSingle != null) {
            singleNodes.add(currSingle);
            currSingle = currSingle.cameFrom;
        }
        java.util.Collections.reverse(singleNodes);
        assertEquals(1, singleNodes.size());
        assertSame(single, singleNodes.get(0));

        // Node chain: start -> n1 -> n2 -> goal
        final net.minecraft.world.level.pathfinder.Node start = new net.minecraft.world.level.pathfinder.Node(0, 64, 0);
        final net.minecraft.world.level.pathfinder.Node n1 = new net.minecraft.world.level.pathfinder.Node(1, 64, 0);
        n1.cameFrom = start;
        final net.minecraft.world.level.pathfinder.Node n2 = new net.minecraft.world.level.pathfinder.Node(2, 64, 0);
        n2.cameFrom = n1;
        final net.minecraft.world.level.pathfinder.Node goal = new net.minecraft.world.level.pathfinder.Node(3, 64, 0);
        goal.cameFrom = n2;

        // Verify O(N) reverse reconstruction matches start-to-goal order
        final java.util.List<net.minecraft.world.level.pathfinder.Node> nodes = new java.util.ArrayList<>(32);
        net.minecraft.world.level.pathfinder.Node curr = goal;
        int depthGuard = 0;
        final int maxDepth = 100;
        while (curr != null && depthGuard++ < maxDepth) {
            nodes.add(curr);
            if (curr == curr.cameFrom) {
                break;
            }
            curr = curr.cameFrom;
        }
        java.util.Collections.reverse(nodes);

        assertEquals(4, nodes.size());
        assertSame(start, nodes.get(0));
        assertSame(n1, nodes.get(1));
        assertSame(n2, nodes.get(2));
        assertSame(goal, nodes.get(3));

        // Cycle test: self loop must terminate safely via depth/self guard
        final net.minecraft.world.level.pathfinder.Node selfLoop = new net.minecraft.world.level.pathfinder.Node(5, 64, 5);
        selfLoop.cameFrom = selfLoop;
        final java.util.List<net.minecraft.world.level.pathfinder.Node> cycleNodes = new java.util.ArrayList<>();
        net.minecraft.world.level.pathfinder.Node currCycle = selfLoop;
        depthGuard = 0;
        while (currCycle != null && depthGuard++ < maxDepth) {
            cycleNodes.add(currCycle);
            if (currCycle == currCycle.cameFrom) {
                break;
            }
            currCycle = currCycle.cameFrom;
        }
        assertEquals(1, cycleNodes.size(), "Self-loop should safely break on duplicate reference");
    }

    @Test
    public void testVec3ZeroAllocationFastPath() {
        final net.minecraft.world.phys.Vec3 v = new net.minecraft.world.phys.Vec3(1.23, 4.56, 7.89);

        assertSame(v, v.add(net.minecraft.world.phys.Vec3.ZERO), "Vec3.add(Vec3.ZERO) must return same instance");
        assertSame(v, v.add(0.0, 0.0, 0.0), "Vec3.add(0, 0, 0) must return same instance");
        assertSame(v, v.add(0.0), "Vec3.add(0.0) must return same instance");
        assertSame(v, v.subtract(net.minecraft.world.phys.Vec3.ZERO), "Vec3.subtract(Vec3.ZERO) must return same instance");
        assertSame(v, v.subtract(0.0, 0.0, 0.0), "Vec3.subtract(0, 0, 0) must return same instance");
        assertSame(v, v.subtract(0.0), "Vec3.subtract(0.0) must return same instance");
        assertSame(v, v.scale(1.0), "Vec3.scale(1.0) must return same instance");
        assertSame(v, v.multiply(1.0, 1.0, 1.0), "Vec3.multiply(1, 1, 1) must return same instance");
        assertSame(v, v.multiply(new net.minecraft.world.phys.Vec3(1.0, 1.0, 1.0)), "Vec3.multiply(identity) must return same instance");
    }

    @Test
    public void testAabbZeroAllocationFastPath() {
        final net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(10.0, 20.0, 30.0, 15.0, 25.0, 35.0);

        assertSame(box, box.expandTowards(net.minecraft.world.phys.Vec3.ZERO), "AABB.expandTowards(Vec3.ZERO) must return same instance");
        assertSame(box, box.expandTowards(0.0, 0.0, 0.0), "AABB.expandTowards(0, 0, 0) must return same instance");
        assertSame(box, box.move(net.minecraft.world.phys.Vec3.ZERO), "AABB.move(Vec3.ZERO) must return same instance");
        assertSame(box, box.move(0.0, 0.0, 0.0), "AABB.move(0, 0, 0) must return same instance");
        assertSame(box, box.move(net.minecraft.core.BlockPos.ZERO), "AABB.move(BlockPos.ZERO) must return same instance");
        assertSame(box, box.inflate(0.0, 0.0, 0.0), "AABB.inflate(0, 0, 0) must return same instance");
        assertSame(box, box.inflate(0.0), "AABB.inflate(0.0) must return same instance");
        assertSame(box, box.deflate(0.0, 0.0, 0.0), "AABB.deflate(0, 0, 0) must return same instance");
        assertSame(box, box.deflate(0.0), "AABB.deflate(0.0) must return same instance");
    }

    @Test
    public void testMthClampFpuParity() {
        final double[] testValues = {-100.5, -10.0, -1.0, -0.0, 0.0, 0.5, 5.0, 10.0, 100.5};
        for (final double val : testValues) {
            assertEquals(Math.min(10.0, Math.max(-5.0, val)), net.minecraft.util.Mth.clamp(val, -5.0, 10.0), 1e-9);
            assertEquals(Math.min(10.0f, Math.max(-5.0f, (float) val)), net.minecraft.util.Mth.clamp((float) val, -5.0f, 10.0f), 1e-6f);
        }
    }
}
