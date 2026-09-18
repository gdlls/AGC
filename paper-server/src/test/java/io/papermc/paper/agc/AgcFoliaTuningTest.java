package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgcFoliaTuning}.
 */
class AgcFoliaTuningTest {

    @BeforeEach
    void cleanState() {
        try {
            AgcFoliaTuning.shutdown();
        } catch (final Throwable ignored) {
        }
    }

    @AfterEach
    void cleanup() {
        try {
            AgcFoliaTuning.shutdown();
        } catch (final Throwable ignored) {
        }
    }

    // Bootstrap / shutdown

    @Test
    void bootstrapIsIdempotent() {
        AgcFoliaTuning.bootstrap();
        AgcFoliaTuning.bootstrap();
        AgcFoliaTuning.bootstrap();
        AgcFoliaTuning.shutdown();
    }

    @Test
    void submitAsyncRunsOnPool() throws InterruptedException {
        AgcFoliaTuning.bootstrap();
        final CountDownLatch latch = new CountDownLatch(1);
        AgcFoliaTuning.submitAsync(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should run within 5s");
    }

    @Test
    void submitAsyncNullTaskThrows() {
        try {
            AgcFoliaTuning.submitAsync(null);
            org.junit.jupiter.api.Assertions.fail("Expected NullPointerException");
        } catch (final NullPointerException expected) {
            // ok
        }
    }

    @Test
    void scheduleAsyncRunsAfterDelay() throws InterruptedException {
        AgcFoliaTuning.bootstrap();
        final CountDownLatch latch = new CountDownLatch(1);
        final long start = System.nanoTime();
        AgcFoliaTuning.scheduleAsync(latch::countDown, 50L);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs >= 40, "Should wait ~50ms, got " + elapsedMs + "ms");
    }

    @Test
    void scheduleAsyncNegativeDelayThrows() {
        try {
            AgcFoliaTuning.scheduleAsync(() -> {}, -1L);
            org.junit.jupiter.api.Assertions.fail("Expected IllegalArgumentException");
        } catch (final IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void scheduleAsyncNullTaskThrows() {
        try {
            AgcFoliaTuning.scheduleAsync(null, 0L);
            org.junit.jupiter.api.Assertions.fail("Expected NullPointerException");
        } catch (final NullPointerException expected) {
            // ok
        }
    }

    @Test
    void submitAsyncBeforeBootstrapRunsSync() {
        final boolean[] ran = {false};
        AgcFoliaTuning.submitAsync(() -> ran[0] = true);
        assertTrue(ran[0]);
    }

    @Test
    void scheduleAsyncBeforeBootstrapRunsSyncAndReturnsNull() {
        final boolean[] ran = {false};
        final var future = AgcFoliaTuning.scheduleAsync(() -> ran[0] = true, 10L);
        assertNull(future, "Should return null when pool not bootstrapped");
        assertTrue(ran[0]);
    }

    @Test
    void shutdownAfterBootstrapIsClean() {
        AgcFoliaTuning.bootstrap();
        AgcFoliaTuning.shutdown();
        AgcFoliaTuning.shutdown();
    }

    @Test
    void statusReportsPoolInfo() {
        AgcFoliaTuning.bootstrap();
        final AgcFoliaTuning.PoolStatus status = AgcFoliaTuning.status();
        assertNotNull(status);
        assertTrue(status.started());
        assertTrue(status.coreSize() > 0);
    }

    // WorldTickBudget

    @Test
    void worldTickBudgetStartsAtZero() {
        final AgcFoliaTuning.WorldTickBudget budget = new AgcFoliaTuning.WorldTickBudget();
        assertEquals(0L, budget.tickCount());
        assertEquals(0.0, budget.averageTickMillis());
    }

    @Test
    void worldTickBudgetAccumulates() throws InterruptedException {
        final AgcFoliaTuning.WorldTickBudget budget = new AgcFoliaTuning.WorldTickBudget();
        for (int i = 0; i < 5; i++) {
            budget.recordTickStart();
            Thread.sleep(2);
            budget.recordTickEnd(10, 5);
        }
        assertEquals(5L, budget.tickCount());
        assertEquals(50L, budget.entitiesTicked());
        assertEquals(25L, budget.chunksTicked());
        assertTrue(budget.averageTickMillis() > 0.0);
    }

    @Test
    void worldTickBudgetIgnoresNegative() {
        final AgcFoliaTuning.WorldTickBudget budget = new AgcFoliaTuning.WorldTickBudget();
        budget.recordTickStart();
        budget.recordTickEnd(-100, -50);
        assertEquals(0L, budget.entitiesTicked());
        assertEquals(0L, budget.chunksTicked());
    }

    @Test
    void worldTickBudgetLastTickMillisIsUpdated() throws InterruptedException {
        final AgcFoliaTuning.WorldTickBudget budget = new AgcFoliaTuning.WorldTickBudget();
        budget.recordTickStart();
        Thread.sleep(10);
        budget.recordTickEnd(0, 0);
        assertTrue(budget.lastTickMillis() >= 5.0, "Should be ~10ms, got " + budget.lastTickMillis());
    }

    @Test
    void worldTickBudgetResetTickWindowKeepsCumulative() {
        final AgcFoliaTuning.WorldTickBudget budget = new AgcFoliaTuning.WorldTickBudget();
        budget.recordTickStart();
        budget.recordTickEnd(10, 5);
        assertEquals(1L, budget.tickCount());
        budget.resetTickWindow();
        assertEquals(1L, budget.tickCount());
        assertEquals(10L, budget.entitiesTicked());
    }

    @Test
    void worldTickBudgetConcurrentRecords() throws InterruptedException {
        final AgcFoliaTuning.WorldTickBudget budget = new AgcFoliaTuning.WorldTickBudget();
        final int threads = 8;
        final int perThread = 100;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (final InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    budget.recordTickStart();
                    budget.recordTickEnd(1, 1);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(threads * perThread, budget.tickCount());
        assertEquals(threads * perThread, budget.entitiesTicked());
    }

    // PoolStatus record

    @Test
    void poolStatusRecordHoldsValues() {
        final AgcFoliaTuning.PoolStatus status = new AgcFoliaTuning.PoolStatus(true, 4, 1, 0);
        assertTrue(status.started());
        assertEquals(4, status.coreSize());
        assertEquals(1, status.activeThreads());
        assertEquals(0, status.queueSize());
    }
}
