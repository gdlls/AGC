package io.papermc.paper.agc.chunk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgcChunkCacheHierarchyTest {

    @BeforeEach
    @AfterEach
    public void setup() {
        AgcChunkCacheHierarchy.get().clear();
    }

    @Test
    public void testL1CacheHitAndMiss() {
        final AgcChunkCacheHierarchy cache = AgcChunkCacheHierarchy.get();
        final Object dummyChunk = new Object();

        assertNull(cache.getL1("world", 10, 20));
        assertEquals(1, cache.metrics().l1Misses());

        cache.putL1("world", 10, 20, dummyChunk);
        assertSame(dummyChunk, cache.getL1("world", 10, 20));
        assertEquals(1, cache.metrics().l1Hits());

        cache.invalidate("world", 10, 20);
        assertNull(cache.getL1("world", 10, 20));
    }

    @Test
    public void testL2CacheStorage() {
        final AgcChunkCacheHierarchy cache = AgcChunkCacheHierarchy.get();
        final byte[] dummyData = new byte[]{1, 2, 3, 4};

        assertNull(cache.getL2("world_nether", 5, -5));
        assertEquals(1, cache.metrics().l2Misses());

        cache.putL2("world_nether", 5, -5, dummyData);
        assertArrayEquals(dummyData, cache.getL2("world_nether", 5, -5));
        assertEquals(1, cache.metrics().l2Hits());
    }

    @Test
    public void testConcurrentLoadDeduplication() throws Exception {
        final AgcChunkCacheHierarchy cache = AgcChunkCacheHierarchy.get();
        final AtomicInteger diskLoads = new AtomicInteger(0);
        final CompletableFuture<Object> pendingDisk = new CompletableFuture<>();

        // Thread 1 initiates load
        final CompletableFuture<Object> f1 = cache.loadChunkDeduplicated("world", 0, 0, () -> {
            diskLoads.incrementAndGet();
            return pendingDisk;
        });

        // Thread 2 requests the same chunk while pending -> must deduplicate
        final CompletableFuture<Object> f2 = cache.loadChunkDeduplicated("world", 0, 0, () -> {
            diskLoads.incrementAndGet();
            return new CompletableFuture<>();
        });

        assertEquals(1, diskLoads.get(), "Disk loader should only be invoked once");
        assertEquals(1, cache.metrics().deduplicatedLoads());

        // Complete the operation
        final Object loadedChunk = new Object();
        pendingDisk.complete(loadedChunk);

        assertSame(loadedChunk, f1.get());
        assertSame(loadedChunk, f2.get());

        // Now present in L1
        assertSame(loadedChunk, cache.getL1("world", 0, 0));
    }

    @Test
    public void testPredictivePrefetchCoordinates() {
        final AgcChunkCacheHierarchy cache = AgcChunkCacheHierarchy.get();

        // Moving east (velX = 10, velZ = 0)
        final int[] pred1 = cache.predictPrefetchChunk(5, 5, 10.0, 0.0, 3);
        assertEquals(8, pred1[0]); // 5 + 3
        assertEquals(5, pred1[1]);

        // Moving south-west (velX = -5, velZ = 5)
        final int[] pred2 = cache.predictPrefetchChunk(10, 10, -5.0, 5.0, 2);
        assertTrue(pred2[0] < 10);
        assertTrue(pred2[1] > 10);

        // Stationary
        final int[] predStationary = cache.predictPrefetchChunk(10, 10, 0.0, 0.0, 3);
        assertEquals(10, predStationary[0]);
        assertEquals(10, predStationary[1]);
    }
}
