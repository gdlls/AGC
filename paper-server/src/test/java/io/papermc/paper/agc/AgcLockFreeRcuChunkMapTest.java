package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgcLockFreeRcuChunkMapTest {

    @BeforeEach
    void setUp() {
        AgcLockFreeRcuChunkMap.get().clear();
    }

    @Test
    void testBasicReadWriteAndGeneration() {
        // Initial state before write
        assertNull(AgcLockFreeRcuChunkMap.get().getSection("world", 0, 0, 4));

        // Update block state at index 100
        final boolean updated = AgcLockFreeRcuChunkMap.get().updateSection(
            "world", 0, 0, 4,
            snapshot -> snapshot.withBlock(100, (short) 1337)
        );

        assertTrue(updated);

        // Lock-free read
        final AgcLockFreeRcuChunkMap.RcuSectionSnapshot snapshot =
            AgcLockFreeRcuChunkMap.get().getSection("world", 0, 0, 4);

        assertNotNull(snapshot);
        assertEquals(4, snapshot.sectionY());
        assertEquals(1L, snapshot.generation());
        assertEquals((short) 1337, snapshot.getBlock(100));
        assertEquals((short) 0, snapshot.getBlock(99));
    }

    @Test
    void testMultiThreadedConcurrentRcuReadersAndWriters() throws InterruptedException {
        final int writerThreads = 4;
        final int readerThreads = 8;
        final int opsPerThread = 500;

        final ExecutorService pool = Executors.newFixedThreadPool(writerThreads + readerThreads);
        final CountDownLatch latch = new CountDownLatch(writerThreads + readerThreads);
        final AtomicInteger readValidations = new AtomicInteger();

        // Writers
        for (int w = 0; w < writerThreads; w++) {
            final int writerId = w;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        final short blockVal = (short) (writerId * 1000 + i);
                        AgcLockFreeRcuChunkMap.get().updateSection(
                            "world_sim", writerId, 0, 0,
                            snapshot -> snapshot.withBlock(0, blockVal)
                        );
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Readers (0ns lock-free reads during concurrent writes)
        for (int r = 0; r < readerThreads; r++) {
            final int targetChunkX = r % writerThreads;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        final AgcLockFreeRcuChunkMap.RcuSectionSnapshot snap =
                            AgcLockFreeRcuChunkMap.get().getSection("world_sim", targetChunkX, 0, 0);
                        if (snap != null) {
                            readValidations.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        final boolean finished = latch.await(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished);
        final AgcLockFreeRcuChunkMap.RcuMetrics metrics = AgcLockFreeRcuChunkMap.get().metrics();
        assertTrue(metrics.totalWrites() >= writerThreads * opsPerThread);
        assertTrue(metrics.totalReads() >= readerThreads * opsPerThread);
        assertEquals(1, metrics.activeWorlds());
        assertEquals(writerThreads, metrics.totalLoadedChunks());
    }

    @Test
    void testCopyOnWritePreservesReaderIsolation() {
        // Step 1: Write initial value
        AgcLockFreeRcuChunkMap.get().updateSection("world", 0, 0, 0, s -> s.withBlock(50, (short) 100));

        // Step 2: Reader takes snapshot (marks shared)
        final AgcLockFreeRcuChunkMap.RcuSectionSnapshot snapshot1 =
            AgcLockFreeRcuChunkMap.get().getSection("world", 0, 0, 0);
        assertNotNull(snapshot1);
        assertEquals((short) 100, snapshot1.getBlock(50));
        assertEquals(1L, snapshot1.generation());

        // Step 3: Writer updates after reader took snapshot -> must COW copy without mutating snapshot1
        AgcLockFreeRcuChunkMap.get().updateSection("world", 0, 0, 0, s -> s.withBlock(50, (short) 200));

        // Snapshot1 still has 100
        assertEquals((short) 100, snapshot1.getBlock(50), "Reader snapshot must remain isolated and immutable");

        // New read has 200 and generation 2
        final AgcLockFreeRcuChunkMap.RcuSectionSnapshot snapshot2 =
            AgcLockFreeRcuChunkMap.get().getSection("world", 0, 0, 0);
        assertNotNull(snapshot2);
        assertEquals((short) 200, snapshot2.getBlock(50));
        assertEquals(2L, snapshot2.generation());
    }
}
