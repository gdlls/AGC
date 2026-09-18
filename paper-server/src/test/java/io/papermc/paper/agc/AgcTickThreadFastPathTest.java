package io.papermc.paper.agc;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@link TickThread#isTickThread()} fast-path latch semantics.
 *
 * The latch (TickThread#VIRTUAL_POSSIBLE) is one-way: while no virtual context has ever been
 * entered, isTickThread() must skip the ThreadLocal probe but still report plain tick threads
 * correctly. Once any context is entered (AgcPluginVirtualizer#enterContext arms the latch),
 * the full check including the virtualizer probe must be active for every thread — a guard
 * check must never miss a virtual primary that exists.
 */
class AgcTickThreadFastPathTest {

    private static class PlainThread extends Thread {
        volatile boolean result;

        PlainThread(final String name) {
            super(name);
        }

        @Override
        public void run() {
            this.result = TickThread.isTickThread();
        }
    }

    private static final class VirtualThread extends Thread {
        volatile boolean result;

        VirtualThread(final String name) {
            super(name);
        }

        @Override
        public void run() {
            // enter a virtual primary context, then probe the guard from this non-tick thread
            try (final AgcPluginVirtualizer.ContextScope ignored =
                     AgcPluginVirtualizer.get().enterContext("test", 0L)) {
                this.result = TickThread.isTickThread();
            } catch (final Exception e) {
                this.result = false;
            }
        }
    }

    @Test
    void tickThreadsAreStillRecognizedWithLatchArmed() throws InterruptedException {
        // Arming the latch must not change the answer for real TickThreads (e.g. the tick loop
        // worker threads): they are instances of TickThread, not virtual primaries.
        AgcPluginVirtualizer.get().runInVirtualPrimary(() -> { /* arm the latch */ });
        final AtomicBoolean result = new AtomicBoolean(false);
        final TickThread tickThread = new TickThread(
            () -> result.set(TickThread.isTickThread()), "agc-test-real-tick-thread");
        tickThread.start();
        tickThread.join(5_000L);
        assertTrue(result.get(), "a real TickThread must be recognized regardless of latch state");
    }

    @Test
    void plainThreadsAreRejectedWithLatchArmed() throws InterruptedException {
        AgcPluginVirtualizer.get().runInVirtualPrimary(() -> { /* arm the latch */ });
        final PlainThread plain = new PlainThread("agc-test-plain-thread");
        plain.start();
        plain.join(5_000L);
        assertFalse(plain.result, "a plain thread with no virtual context must fail the guard");
    }

    @Test
    void virtualPrimaryIsRecognizedAfterLatchIsArmed() throws InterruptedException {
        AgcPluginVirtualizer.get().runInVirtualPrimary(() -> { /* arm the latch */ });
        final VirtualThread virtual = new VirtualThread("agc-test-virtual-thread");
        virtual.start();
        virtual.join(5_000L);
        assertTrue(virtual.result,
            "after the latch is armed, a thread inside a virtual primary context must pass the guard");
    }

    @Test
    void guardAnswersAreConsistentUnderConcurrentProbing() throws InterruptedException {
        AgcPluginVirtualizer.get().runInVirtualPrimary(() -> { /* arm the latch */ });
        final int readers = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(readers);
        final AtomicBoolean failure = new AtomicBoolean(false);
        for (int i = 0; i < readers; i++) {
            final PlainThread plain = new PlainThread("agc-test-concurrent-" + i) {
                @Override
                public void run() {
                    boolean ok = true;
                    try {
                        start.await();
                        // probe repeatedly: any true for a plain thread would mean a missed guard
                        for (int j = 0; j < 2_000; j++) {
                            if (TickThread.isTickThread()) {
                                ok = false;
                                break;
                            }
                        }
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        ok = false;
                    } finally {
                        if (!ok) {
                            failure.set(true);
                        }
                        done.countDown();
                    }
                }
            };
            plain.start();
        }
        start.countDown();
        assertTrue(done.await(30_000L, java.util.concurrent.TimeUnit.MILLISECONDS), "readers did not finish");
        assertFalse(failure.get(), "plain threads must never pass the guard once armed");
    }
}
