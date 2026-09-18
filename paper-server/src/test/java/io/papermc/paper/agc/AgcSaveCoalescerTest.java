package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link AgcSaveCoalescer}: the expensive save action must never run
 * concurrently with itself, a burst of requests must collapse to at most one extra run, and the
 * last request of a burst must still be persisted.
 */
class AgcSaveCoalescerTest {

    @Test
    void singleRequestRunsTheActionOnce() {
        final AgcSaveCoalescer coalescer = new AgcSaveCoalescer();
        final AtomicInteger runs = new AtomicInteger();

        assertTrue(coalescer.request(), "the first request must claim the writer role");
        assertEquals(1, coalescer.drain(runs::incrementAndGet));

        assertEquals(1, runs.get());
        assertEquals(1L, coalescer.requests());
        assertEquals(0L, coalescer.rejected());
        assertFalse(coalescer.isWriterActive(), "the writer role must be free again");
        assertFalse(coalescer.hasPendingRequest(), "no request may be left pending");
    }

    @Test
    void requestsArrivingDuringAWriteCollapseIntoASingleTrailingWrite() {
        final AgcSaveCoalescer coalescer = new AgcSaveCoalescer();
        final AtomicInteger runs = new AtomicInteger();
        final AtomicLong observedWritesAtFirstRun = new AtomicLong(-1);

        // The first execution of the action itself issues 500 more requests, i.e. exactly the shape
        // of a join burst where every arriving player asks the user cache to persist itself again.
        final Runnable action = () -> {
            final int run = runs.incrementAndGet();
            if (run == 1) {
                for (int i = 0; i < 500; i++) {
                    coalescer.request();
                }
                observedWritesAtFirstRun.set(coalescer.requests());
            }
        };

        assertTrue(coalescer.request());
        final int performed = coalescer.drain(action);

        assertEquals(2, performed, "a burst must collapse into one run plus one trailing run");
        assertEquals(2, runs.get());
        assertEquals(501L, observedWritesAtFirstRun.get());
        assertFalse(coalescer.hasPendingRequest(), "the burst must have been fully drained");
    }

    @Test
    void actionIsNeverExecutedConcurrently() throws Exception {
        final AgcSaveCoalescer coalescer = new AgcSaveCoalescer();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger runs = new AtomicInteger();
        final AtomicBoolean stop = new AtomicBoolean();

        final Runnable action = () -> {
            final int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            runs.incrementAndGet();
            Thread.onSpinWait();
            inFlight.decrementAndGet();
        };

        final int threads = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (final InterruptedException e) {
                    return;
                }
                while (!stop.get()) {
                    if (coalescer.request()) {
                        try {
                            while (coalescer.consume()) {
                                action.run();
                            }
                        } finally {
                            coalescer.release();
                        }
                    }
                }
            });
        }

        start.countDown();
        Thread.sleep(250);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers must stop");

        assertEquals(1, maxInFlight.get(), "the save action must never run concurrently with itself");
        assertTrue(runs.get() > 1, "the burst must still make progress, got " + runs.get());
        assertEquals(runs.get(), (int) coalescer.writes(),
            "the write counter must match the number of action executions");
        assertTrue(coalescer.rejected() > 0, "a burst of this size must have been coalesced");
    }

    @Test
    void releaseHandsTheRoleToALateRequest() {
        final AgcSaveCoalescer coalescer = new AgcSaveCoalescer();

        assertTrue(coalescer.request());
        assertTrue(coalescer.consume());
        // A request lands after the writer consumed the previous one but before it releases.
        assertFalse(coalescer.request(), "the second request must be coalesced into the active writer");
        assertTrue(coalescer.release(), "ownership must be retained for the pending request");
        assertTrue(coalescer.consume(), "the late request must still be served");
        assertFalse(coalescer.release(), "with nothing pending the role is released for good");
        assertFalse(coalescer.isWriterActive());
    }
}
