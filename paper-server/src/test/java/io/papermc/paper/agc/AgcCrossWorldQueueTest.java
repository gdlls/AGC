package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcCrossWorldQueue}.
 */
class AgcCrossWorldQueueTest {

    @BeforeEach
    @AfterEach
    void resetQueue() {
        AgcCrossWorldQueue.get().resetMetrics();
    }

    @Test
    void queueStartsEmpty() {
        assertFalse(AgcCrossWorldQueue.get().hasPending());
        assertEquals(0, AgcCrossWorldQueue.get().size());
        final AgcCrossWorldQueue.QueueMetrics m = AgcCrossWorldQueue.get().metrics();
        assertEquals(0, m.enqueued());
        assertEquals(0, m.drained());
        assertEquals(0, m.errors());
    }

    @Test
    void enqueueAndDrainSingleOperation() {
        final AtomicInteger counter = new AtomicInteger(0);
        AgcCrossWorldQueue.get().enqueue("test-op", () -> counter.incrementAndGet());

        assertTrue(AgcCrossWorldQueue.get().hasPending());
        assertEquals(1, AgcCrossWorldQueue.get().size());

        final int drained = AgcCrossWorldQueue.get().drainAll();
        assertEquals(1, drained);
        assertEquals(1, counter.get());
        assertFalse(AgcCrossWorldQueue.get().hasPending());

        final AgcCrossWorldQueue.QueueMetrics m = AgcCrossWorldQueue.get().metrics();
        assertEquals(1, m.enqueued());
        assertEquals(1, m.drained());
        assertEquals(0, m.errors());
    }

    @Test
    void boundedDrainExecutesUpToLimit() {
        final AtomicInteger counter = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            AgcCrossWorldQueue.get().enqueue("batch-op", counter::incrementAndGet);
        }

        assertEquals(10, AgcCrossWorldQueue.get().size());
        final int firstDrain = AgcCrossWorldQueue.get().drain(4);
        assertEquals(4, firstDrain);
        assertEquals(4, counter.get());
        assertEquals(6, AgcCrossWorldQueue.get().size());

        final int remainingDrain = AgcCrossWorldQueue.get().drainAll();
        assertEquals(6, remainingDrain);
        assertEquals(10, counter.get());
        assertEquals(0, AgcCrossWorldQueue.get().size());
    }

    @Test
    void errorInActionDoesNotBreakDrain() {
        final AtomicInteger counter = new AtomicInteger(0);
        AgcCrossWorldQueue.get().enqueue("failing-op", () -> {
            throw new RuntimeException("Simulated action failure");
        });
        AgcCrossWorldQueue.get().enqueue("success-op", counter::incrementAndGet);

        final int drained = AgcCrossWorldQueue.get().drainAll();
        assertEquals(1, drained);
        assertEquals(1, counter.get());

        final AgcCrossWorldQueue.QueueMetrics m = AgcCrossWorldQueue.get().metrics();
        assertEquals(2, m.enqueued());
        assertEquals(1, m.drained());
        assertEquals(1, m.errors());
    }

    @Test
    void nullActionThrows() {
        assertThrows(NullPointerException.class, () -> AgcCrossWorldQueue.get().enqueue("test", null));
    }

    @Test
    void concurrentEnqueueAndDrain() throws InterruptedException {
        final int threads = 8;
        final int opsPerThread = 500;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        final CountDownLatch latch = new CountDownLatch(threads);
        final AtomicInteger executed = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        AgcCrossWorldQueue.get().enqueue("concurrent-op", executed::incrementAndGet);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threads * opsPerThread, AgcCrossWorldQueue.get().size());
        final int drained = AgcCrossWorldQueue.get().drainAll();
        assertEquals(threads * opsPerThread, drained);
        assertEquals(threads * opsPerThread, executed.get());
    }
}
