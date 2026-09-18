package io.papermc.paper.agc.chunk;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcChunkSendCacheTest {

    private static final Object PAYLOAD_A = new Object();
    private static final Object PAYLOAD_B = new Object();

    @Test
    public void testHitOnSameEpoch() {
        AgcChunkSendCache.get().clear();
        final long key = 0x1234L;
        AgcChunkSendCache.get().store(key, 42L, PAYLOAD_A, 8192L);
        final Object hit = AgcChunkSendCache.get().getIfCurrent(key, 42L);
        assertNotNull(hit);
        assertEquals(1L, AgcChunkSendCache.get().metrics().hits());
        assertEquals(1L, AgcChunkSendCache.get().metrics().misses());
    }

    @Test
    public void testStaleEpochDrops() {
        AgcChunkSendCache.get().clear();
        final long key = 0x2345L;
        AgcChunkSendCache.get().store(key, 7L, PAYLOAD_A, 8192L);
        // content mutated -> epoch advanced
        assertNull(AgcChunkSendCache.get().getIfCurrent(key, 8L));
        assertEquals(1L, AgcChunkSendCache.get().metrics().staleDrops());
        // entry is gone after the lazy drop
        assertNull(AgcChunkSendCache.get().getIfCurrent(key, 8L));
    }

    @Test
    public void testInstanceEpochCollisionIsHarmless() {
        // Simulates unload->reload: same cache key, different instance id (epoch base differs)
        AgcChunkSendCache.get().clear();
        final long key = 0x3456L;
        // instance 1, mutated 3 times
        final long epoch1 = (1L << 32) | 3L;
        AgcChunkSendCache.get().store(key, epoch1, PAYLOAD_A, 8192L);
        // instance 1 with its own epoch hits
        assertNotNull(AgcChunkSendCache.get().getIfCurrent(key, epoch1));
        // instance 2 (same key, never mutated) must NOT hit instance 1's entry,
        // and the dead entry is dropped (only one live chunk instance per key)
        assertNull(AgcChunkSendCache.get().getIfCurrent(key, (2L << 32)));
        assertNull(AgcChunkSendCache.get().getIfCurrent(key, epoch1));
        assertEquals(1L, AgcChunkSendCache.get().metrics().staleDrops());
    }

    @Test
    public void testOverwriteReplacesAndAccountsBytes() {
        AgcChunkSendCache.get().clear();
        final long key = 0x4567L;
        AgcChunkSendCache.get().store(key, 1L, PAYLOAD_A, 8192L);
        AgcChunkSendCache.get().store(key, 2L, PAYLOAD_B, 4096L);
        assertEquals(1, AgcChunkSendCache.get().metrics().entries());
        assertEquals(4096L, AgcChunkSendCache.get().metrics().bytesStored());
        assertNull(AgcChunkSendCache.get().getIfCurrent(key, 1L)); // old epoch stale
        assertEquals(PAYLOAD_B, AgcChunkSendCache.get().getIfCurrent(key, 2L));
    }

    @Test
    public void testZeroSizeAndNullRejected() {
        AgcChunkSendCache.get().clear();
        AgcChunkSendCache.get().store(0x5678L, 1L, PAYLOAD_A, 0L);
        AgcChunkSendCache.get().store(0x5679L, 1L, null, 8192L);
        assertEquals(0, AgcChunkSendCache.get().metrics().entries());
    }

    @Test
    public void testByteCapEviction() {
        AgcChunkSendCache.get().clear();
        // store entries with big sizes to force eviction sweeps
        for (int i = 0; i < 64; i++) {
            AgcChunkSendCache.get().store(0x100000L + i, 1L, PAYLOAD_A, 1024L * 1024L);
        }
        assertTrue(AgcChunkSendCache.get().metrics().bytesStored() <= 24L * 1024L * 1024L + 1024L * 1024L);
    }

    @Test
    public void testConcurrentHitAndStale() throws Exception {
        AgcChunkSendCache.get().clear();
        final long key = 0x6789L;
        AgcChunkSendCache.get().store(key, 5L, PAYLOAD_A, 8192L);
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        final AtomicInteger hits = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                latch.countDown();
                for (int i = 0; i < 10_000; i++) {
                    if (AgcChunkSendCache.get().getIfCurrent(key, 5L) != null) {
                        hits.incrementAndGet();
                    }
                }
            });
        }
        latch.await();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(80_000, hits.get());
        AgcChunkSendCache.get().clear();
    }
}
