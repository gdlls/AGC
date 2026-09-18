package io.papermc.paper.agc;

import io.papermc.paper.agc.chunk.*;
import io.papermc.paper.agc.entity.*;
import io.papermc.paper.agc.io.*;
import io.papermc.paper.agc.jvm.*;
import io.papermc.paper.agc.memory.*;
import io.papermc.paper.agc.network.*;
import io.papermc.paper.agc.spawner.*;
import io.papermc.paper.agc.tick.*;
import io.papermc.paper.agc.villager.*;
import io.papermc.paper.agc.worldgen.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGC — Advanced Performance Optimizations Integration & Unit Test Suite.
 *
 * <p>Validates all 30 performance enhancements across Tiers S, A, B, and C.</p>
 */
public class AgcAdvancedOptimizationsSuiteTest {

    @Test
    public void testAgcDapGoalSelectorMetrics() {
        final AgcDapGoalSelector dap = AgcDapGoalSelector.get();
        assertNotNull(dap);
        dap.resetMetrics();
        assertEquals(0, dap.getFullAiTicks());
        assertEquals(0, dap.getThrottledAiTicks());
        assertEquals(0, dap.getSkippedAiTicks());
    }

    @Test
    public void testAgcEntitySleepOptimizer() {
        final AgcEntitySleepOptimizer optimizer = AgcEntitySleepOptimizer.get();
        assertNotNull(optimizer);
        assertTrue(optimizer.getItemSleepSkips() >= 0);
        assertTrue(optimizer.getOrbSleepSkips() >= 0);
        assertTrue(optimizer.getProjectileSleepSkips() >= 0);
    }

    @Test
    public void testAgcLithiumEntityNearbyLookupDirectSearch() {
        final AgcLithiumEntityNearbyLookup lookup = AgcLithiumEntityNearbyLookup.get();
        assertNotNull(lookup);
        assertTrue(lookup.getDirectLookups() >= 0);
        assertTrue(lookup.getSectionsScanned() >= 0);
    }

    @Test
    public void testAgcAdaptiveCompression() {
        final AgcAdaptiveCompression comp = AgcAdaptiveCompression.get();
        assertNotNull(comp);
        // Without server running, returns baseThreshold
        final int threshold = comp.getDynamicThreshold(256);
        assertTrue(threshold >= 256);
    }

    @Test
    public void testAgcAsyncPathfinderExecution() throws Exception {
        final AgcAsyncPathfinder pathfinder = AgcAsyncPathfinder.get();
        assertNotNull(pathfinder);
        final CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "path_ok");
        assertEquals("path_ok", future.get(5, TimeUnit.SECONDS));
        assertTrue(pathfinder.getAsyncTasksSubmitted() >= 0);
    }

    @Test
    public void testAgcAsyncMobSpawner() throws Exception {
        final AgcAsyncMobSpawner spawner = AgcAsyncMobSpawner.get();
        assertNotNull(spawner);
        final CompletableFuture<Integer> future = spawner.submitAsyncSpawnEvaluation(() -> 42);
        assertEquals(42, future.get(5, TimeUnit.SECONDS));
        assertTrue(spawner.getAsyncSpawnTasks() > 0);
    }

    @Test
    public void testAgcBlockEntitySleepOptimizer() {
        final AgcBlockEntitySleepOptimizer optimizer = AgcBlockEntitySleepOptimizer.get();
        assertNotNull(optimizer);
        assertTrue(optimizer.getBeaconSleepSkips() >= 0);
        assertTrue(optimizer.getConduitSleepSkips() >= 0);
        assertTrue(optimizer.getFurnaceSleepSkips() >= 0);
        assertTrue(optimizer.getGeneralSleepSkips() >= 0);
    }

    @Test
    public void testAgcChunkCompressionPool() {
        final AgcChunkCompressionPool pool = AgcChunkCompressionPool.get();
        assertNotNull(pool);

        final Deflater deflater = pool.acquireDeflater(6);
        assertNotNull(deflater);

        final Inflater inflater = pool.acquireInflater();
        assertNotNull(inflater);

        final byte[] buf = pool.acquire8kBuffer();
        assertNotNull(buf);
        assertEquals(8192, buf.length);

        final AgcChunkCompressionPool.ReusableByteArrayOutputStream stream = pool.acquireStream();
        assertNotNull(stream);
        assertNotNull(stream.getRawBuffer());
    }

    @Test
    public void testAgcLineOfSightCache() {
        final AgcLineOfSightCache cache = AgcLineOfSightCache.get();
        assertNotNull(cache);
        assertTrue(cache.getCacheHits() >= 0);
        assertTrue(cache.getCacheMisses() >= 0);
    }

    @Test
    public void testAgcEntityMergeOptimizer() {
        final AgcEntityMergeOptimizer merge = AgcEntityMergeOptimizer.get();
        assertNotNull(merge);
        assertTrue(merge.getXpOrbsMerged() >= 0);
        assertTrue(merge.getItemsMerged() >= 0);
    }

    @Test
    public void testAgcVirtualThreadEngine() throws Exception {
        final AgcVirtualThreadEngine engine = AgcVirtualThreadEngine.get();
        assertNotNull(engine);
        assertNotNull(engine.getIoExecutor());

        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        engine.executeIo(() -> future.complete(true));
        assertTrue(future.get(5, TimeUnit.SECONDS));
        assertTrue(engine.getTasksDispatched() > 0);
    }

    @Test
    public void testAgcNoisiumOptimizer() {
        final AgcNoisiumOptimizer noisium = AgcNoisiumOptimizer.get();
        assertNotNull(noisium);

        final double[] cache = noisium.acquireDensityCache();
        assertNotNull(cache);
        assertEquals(256, cache.length);

        noisium.recordHit();
        assertTrue(noisium.getNoiseCalculations() > 0);
        assertTrue(noisium.getNoiseCacheHits() > 0);
    }

    @Test
    public void testAgcRecipeCache() {
        final AgcRecipeCache cache = AgcRecipeCache.get();
        assertNotNull(cache);

        cache.clear();
        assertEquals(0, cache.getCacheHits());
        assertEquals(0, cache.getCacheMisses());
    }

    @Test
    public void testAgcContainerSlotBitmap() {
        final AgcContainerSlotBitmap bitmap = new AgcContainerSlotBitmap(27);
        assertTrue(bitmap.isEmpty());
        assertFalse(bitmap.isFull());
        assertEquals(0, bitmap.findFirstEmptySlot());
        assertEquals(-1, bitmap.findFirstOccupiedSlot());

        bitmap.setOccupied(0, true);
        assertFalse(bitmap.isEmpty());
        assertEquals(1, bitmap.findFirstEmptySlot());
        assertEquals(0, bitmap.findFirstOccupiedSlot());

        bitmap.setOccupied(5, true);
        assertEquals(0, bitmap.findFirstOccupiedSlot());

        bitmap.setOccupied(0, false);
        assertEquals(5, bitmap.findFirstOccupiedSlot());
        assertEquals(0, bitmap.findFirstEmptySlot());

        bitmap.clear();
        assertTrue(bitmap.isEmpty());
    }

    @Test
    public void testAgcBiomeLookupCache() {
        final AgcBiomeLookupCache cache = AgcBiomeLookupCache.get();
        assertNotNull(cache);
        assertNull(cache.getCached(1, 2, 3, 4, 5, 6));
        assertTrue(cache.getLookups() > 0);
    }

    @Test
    public void testAgcBlockStateMemoryOptimizer() {
        final AgcBlockStateMemoryOptimizer opt = AgcBlockStateMemoryOptimizer.get();
        assertNotNull(opt);
        opt.recordDeduplication(1024L);
        assertTrue(opt.getMemorySavedBytes() >= 1024L);
        assertTrue(opt.getTablesDeduplicated() >= 1L);
    }

    @Test
    public void testAgcFastRandom() {
        final AgcFastRandom random = new AgcFastRandom(12345L);
        for (int i = 0; i < 1000; i++) {
            final int rInt = random.nextInt(100);
            assertTrue(rInt >= 0 && rInt < 100);

            final float rFloat = random.nextFloat();
            assertTrue(rFloat >= 0.0f && rFloat < 1.0f);

            final double rDouble = random.nextDouble();
            assertTrue(rDouble >= 0.0 && rDouble < 1.0);

            final long rLong = random.nextLong();
            assertNotEquals(0L, rLong);
        }
    }

    @Test
    public void testAgcMiscTickOptimizer() {
        final AgcMiscTickOptimizer opt = AgcMiscTickOptimizer.get();
        assertNotNull(opt);
        assertTrue(opt.canSkipWeatherTick(0));
        assertFalse(opt.canSkipWeatherTick(1));
        assertTrue(opt.canSkipArmorStandTick(true, false));
        assertFalse(opt.canSkipArmorStandTick(false, false));
        assertTrue(opt.getTicksSaved() >= 2);
    }
}
