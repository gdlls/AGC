package io.papermc.paper.agc.world;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcWorldMemoryManagerTest {

    @BeforeEach
    public void setup() {
        AgcWorldMemoryManager.get().clear();
    }

    @Test
    public void testPerWorldBudgetCalculation() {
        AgcWorldMemoryManager manager = AgcWorldMemoryManager.get();

        // 8GB Heap, 10 worlds -> 60% = 4.8GB / 10 = ~480MB
        long budget10 = manager.calculatePerWorldBudget(8L * 1024 * 1024 * 1024L, 10);
        assertTrue(budget10 >= 400 * 1024 * 1024L);

        // 8GB Heap, 100 worlds -> 48MB per world
        long budget100 = manager.calculatePerWorldBudget(8L * 1024 * 1024 * 1024L, 100);
        assertTrue(budget100 >= 32 * 1024 * 1024L); // Floor constraint 32MB
    }

    @Test
    public void testChunkEvictionEvaluation() {
        AgcWorldMemoryManager manager = AgcWorldMemoryManager.get();

        // Inside view distance -> never evict
        assertFalse(manager.evaluateChunkEviction("world", 0, 0, true, 0, 5000, false));

        // Outside view distance, but accessed recently (500 ticks ago < 1200 ticks threshold) -> keep
        assertFalse(manager.evaluateChunkEviction("world", 0, 0, false, 4500, 5000, false));

        // Outside view distance, idle for 2000 ticks (> 1200 ticks threshold), clean -> evict
        assertTrue(manager.evaluateChunkEviction("world", 0, 0, false, 3000, 5000, false));
        assertEquals(1, manager.metrics().cleanChunksEvicted());

        // Dirty chunk -> marked for eviction & staging
        assertTrue(manager.evaluateChunkEviction("world", 1, 1, false, 3000, 5000, true));
        assertEquals(1, manager.metrics().dirtyChunksStaged());
    }

    @Test
    public void testSoftReferenceChunkCache() {
        AgcWorldMemoryManager manager = AgcWorldMemoryManager.get();
        Object chunkPayload = new byte[]{1, 2, 3, 4};

        manager.retainSoftChunk("world_nether", 5, 5, chunkPayload);
        assertEquals(1, manager.metrics().softCachedChunks());

        Object retrieved = manager.retrieveSoftChunk("world_nether", 5, 5);
        assertNotNull(retrieved);
        assertEquals(chunkPayload, retrieved);
        assertEquals(1, manager.metrics().softCacheHits());

        // After retrieval, cache entry is consumed
        assertNull(manager.retrieveSoftChunk("world_nether", 5, 5));
    }
}
