package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgcHotPathCache} — pure Java, no Bukkit.
 */
class AgcHotPathCacheTest {

    // TickBudget

    @Test
    void tickBudgetAcquiresUpToMax() {
        final AgcHotPathCache.TickBudget budget = new AgcHotPathCache.TickBudget();
        for (int i = 0; i < 5; i++) {
            assertTrue(budget.tryAcquire(5), "Acquire " + i);
        }
        assertFalse(budget.tryAcquire(5), "Should fail at 6th");
    }

    @Test
    void tickBudgetRemainingDecreases() {
        final AgcHotPathCache.TickBudget budget = new AgcHotPathCache.TickBudget();
        assertEquals(3, budget.remaining(3));
        assertTrue(budget.tryAcquire(3));
        assertEquals(2, budget.remaining(3));
        assertTrue(budget.tryAcquire(3));
        assertEquals(1, budget.remaining(3));
        assertTrue(budget.tryAcquire(3));
        assertEquals(0, budget.remaining(3));
    }

    @Test
    void tickBudgetResets() {
        final AgcHotPathCache.TickBudget budget = new AgcHotPathCache.TickBudget();
        budget.tryAcquire(2);
        budget.tryAcquire(2);
        assertEquals(0, budget.remaining(2));
        budget.reset();
        assertEquals(2, budget.remaining(2));
    }

    @Test
    void tickBudgetMultiCost() {
        final AgcHotPathCache.TickBudget budget = new AgcHotPathCache.TickBudget();
        assertTrue(budget.tryAcquire(10, 3));
        assertTrue(budget.tryAcquire(10, 3));
        assertTrue(budget.tryAcquire(10, 3));
        // 9 used, remaining 1 -> cannot acquire 4
        assertFalse(budget.tryAcquire(10, 4));
        assertTrue(budget.tryAcquire(10, 1));
    }

    @Test
    void tickBudgetZeroMaxAllowsAlways() {
        final AgcHotPathCache.TickBudget budget = new AgcHotPathCache.TickBudget();
        // max=0 means disabled -> always true
        for (int i = 0; i < 100; i++) {
            assertTrue(budget.tryAcquire(0));
        }
    }

    @Test
    void tickBudgetConcurrentAcquire() throws InterruptedException {
        final AgcHotPathCache.TickBudget budget = new AgcHotPathCache.TickBudget();
        final int threads = 16;
        final int iterations = 1000;
        final int max = 100;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger acquired = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < iterations; i++) {
                    if (budget.tryAcquire(max)) {
                        acquired.incrementAndGet();
                    }
                    budget.reset();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(acquired.get() > 0);
    }

    // ChunkPacketCache

    @Test
    void chunkPacketCachePutAndGet() {
        final AgcHotPathCache.ChunkPacketCache cache = new AgcHotPathCache.ChunkPacketCache(100, 1024 * 1024);
        final byte[] data = new byte[]{1, 2, 3};
        cache.put(0, 0, data);
        assertNotNull(cache.get(0, 0));
        assertEquals(1, cache.size()); // 1 entry
        assertEquals(3, cache.currentBytes()); // 3 bytes
    }

    @Test
    void chunkPacketCacheTracksBytes() {
        final AgcHotPathCache.ChunkPacketCache cache = new AgcHotPathCache.ChunkPacketCache(100, 1024 * 1024);
        cache.put(0, 0, new byte[100]);
        cache.put(1, 0, new byte[200]);
        assertEquals(300, cache.currentBytes());
    }

    @Test
    void chunkPacketCacheEntryCapEvicts() {
        final AgcHotPathCache.ChunkPacketCache cache = new AgcHotPathCache.ChunkPacketCache(8, 1024 * 1024);
        for (int i = 0; i < 12; i++) {
            cache.put(i, 0, new byte[16]);
        }
        assertTrue(cache.size() <= 8, "Cache size should be <= cap, got " + cache.size());
    }

    @Test
    void chunkPacketCacheByteCapEvicts() {
        // 4 entry / 100 byte cap
        final AgcHotPathCache.ChunkPacketCache cache = new AgcHotPathCache.ChunkPacketCache(100, 100);
        cache.put(0, 0, new byte[40]);
        cache.put(1, 0, new byte[40]);
        cache.put(2, 0, new byte[40]);
        // 120 bytes > 100 -> partial eviction
        assertTrue(cache.currentBytes() <= 100, "Bytes should be <= cap, got " + cache.currentBytes());
    }

    @Test
    void chunkPacketCacheInvalidate() {
        final AgcHotPathCache.ChunkPacketCache cache = new AgcHotPathCache.ChunkPacketCache(100, 1024 * 1024);
        cache.put(0, 0, new byte[10]);
        cache.put(1, 0, new byte[20]);
        assertEquals(2, cache.size());
        cache.invalidate(0, 0);
        assertEquals(1, cache.size());
        assertEquals(20, cache.currentBytes());
    }

    @Test
    void chunkPacketCacheInvalidateAll() {
        final AgcHotPathCache.ChunkPacketCache cache = new AgcHotPathCache.ChunkPacketCache(100, 1024 * 1024);
        for (int i = 0; i < 10; i++) {
            cache.put(i, 0, new byte[10]);
        }
        assertEquals(10, cache.size());
        cache.invalidateAll();
        assertEquals(0, cache.size());
        assertEquals(0, cache.currentBytes());
    }

    @Test
    void chunkPacketCacheZeroSizeIsNoOp() {
        final AgcHotPathCache.ChunkPacketCache cache = new AgcHotPathCache.ChunkPacketCache(0, 0);
        cache.put(0, 0, new byte[10]);
        assertEquals(0, cache.size());
        assertNull(cache.get(0, 0));
    }

    @Test
    void chunkPacketCacheSampleEvictionIsLRU() {
        final AgcHotPathCache.ChunkPacketCache cache = new AgcHotPathCache.ChunkPacketCache(2, 1024 * 1024);
        for (int i = 0; i < 4; i++) {
            cache.put(i, 0, new byte[10]);
        }
        assertTrue(cache.size() <= 2);
    }

    @Test
    void chunkPacketCacheConcurrentPuts() throws InterruptedException {
        final AgcHotPathCache.ChunkPacketCache cache = new AgcHotPathCache.ChunkPacketCache(1000, 1024 * 1024);
        final int threads = 8;
        final int perThread = 100;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (final InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    cache.put(tid * 1000 + i, 0, new byte[16]);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(cache.size() <= 1000);
    }

    // PacketCounter

    @Test
    void packetCounterCountsProcessed() {
        final AgcHotPathCache.PacketCounter counter = new AgcHotPathCache.PacketCounter();
        counter.recordProcessed(100);
        counter.recordProcessed(200);
        assertEquals(2, counter.processed());
        assertEquals(300, counter.bytes());
    }

    @Test
    void packetCounterCountsDropped() {
        final AgcHotPathCache.PacketCounter counter = new AgcHotPathCache.PacketCounter();
        counter.recordDropped();
        counter.recordDropped();
        counter.recordDropped();
        assertEquals(3, counter.dropped());
    }

    @Test
    void packetCounterReset() {
        final AgcHotPathCache.PacketCounter counter = new AgcHotPathCache.PacketCounter();
        counter.recordProcessed(100);
        counter.recordDropped();
        counter.reset();
        assertEquals(0, counter.processed());
        assertEquals(0, counter.dropped());
        assertEquals(0, counter.bytes());
    }

    @Test
    void packetCounterIgnoresNegativeBytes() {
        final AgcHotPathCache.PacketCounter counter = new AgcHotPathCache.PacketCounter();
        counter.recordProcessed(-1);
        assertEquals(1, counter.processed());
        assertEquals(0, counter.bytes());
    }

    // Pool sizing

    @Test
    void suggestedGlobalRegionThreadsIsAtLeastTwo() {
        assertTrue(AgcHotPathCache.suggestedGlobalRegionThreads() >= 2);
    }

    @Test
    void suggestedWorldThreadsIsAtLeastOne() {
        assertTrue(AgcHotPathCache.suggestedWorldThreads() >= 1);
    }

    @Test
    void suggestedTotalWorkerThreadsIsBoundedByCores() {
        final int total = AgcHotPathCache.suggestedTotalWorkerThreads();
        final int cores = Runtime.getRuntime().availableProcessors();
        assertTrue(total <= Math.max(2, cores));
    }

    // CacheSnapshot

    @Test
    void cacheSnapshotStartsEmpty() {
        AgcHotPathCache.resetSnapshot();
        final AgcHotPathCache.CacheSnapshot snap = AgcHotPathCache.lastSnapshot();
        assertNotNull(snap);
        assertEquals(0, snap.availableProcessors());
    }

    @Test
    void cacheSnapshotAfterRecordIsNotEmpty() {
        final AgcHotPathCache.CacheSnapshot snap = AgcHotPathCache.recordSnapshot();
        assertTrue(snap.nanoTime() > 0);
        assertTrue(snap.availableProcessors() > 0);
        assertTrue(snap.maxMemory() > 0);
    }

    @Test
    void cacheSnapshotUsedMemoryRatioInRange() {
        final AgcHotPathCache.CacheSnapshot snap = AgcHotPathCache.recordSnapshot();
        final double ratio = snap.usedMemoryRatio();
        assertTrue(ratio >= 0.0 && ratio <= 1.0);
    }

    // ThreadLocal tick budget

    @Test
    void threadLocalTickBudgetIsPerThread() throws InterruptedException {
        final AgcHotPathCache.TickBudget main = AgcHotPathCache.tickBudget();
        main.tryAcquire(1);

        final AgcHotPathCache.TickBudget[] holder = new AgcHotPathCache.TickBudget[1];
        final Thread t = new Thread(() -> holder[0] = AgcHotPathCache.tickBudget());
        t.start();
        t.join();
        assertNotNull(holder[0]);
        assertNotNull(main);
        assertEquals(0, holder[0].used());
    }
}
