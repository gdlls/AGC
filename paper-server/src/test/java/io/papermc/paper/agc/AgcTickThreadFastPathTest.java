package io.papermc.paper.agc;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@link TickThread#isTickThread()} contract after the virtual-primary escape hatch was
 * removed.
 *
 * <p>History: the guard used to also return {@code true} for any thread that had entered an
 * {@link AgcPluginVirtualizer} "virtual primary" context. Parallel world-tick workers entered such a
 * context for bookkeeping, which meant a worker thread passing this guard while plugins still
 * believed they were on the main thread - the exact invariant the guard exists to protect. The
 * escape hatch is gone: a tick thread is a {@link TickThread} instance, nothing else.</p>
 */
class AgcTickThreadFastPathTest {

    private static class ProbingThread extends Thread {
        volatile boolean result;
        private final boolean virtualContext;

        ProbingThread(final String name, final boolean virtualContext) {
            super(name);
            this.virtualContext = virtualContext;
        }

        @Override
        public void run() {
            if (this.virtualContext) {
                try (final AgcPluginVirtualizer.ContextScope ignored =
                         AgcPluginVirtualizer.get().enterContext("test", 0L)) {
                    this.result = TickThread.isTickThread();
                } catch (final Exception e) {
                    this.result = true; // fail loudly through the assertion below
                }
            } else {
                this.result = TickThread.isTickThread();
            }
        }
    }

    @Test
    void realTickThreadsAreRecognized() throws InterruptedException {
        final AtomicBoolean result = new AtomicBoolean(false);
        final TickThread tickThread = new TickThread(
            () -> result.set(TickThread.isTickThread()), "agc-test-real-tick-thread");
        tickThread.start();
        tickThread.join(5_000L);
        assertTrue(result.get(), "a real TickThread must pass the guard");
    }

    @Test
    void plainThreadsAreRejected() throws InterruptedException {
        final ProbingThread plain = new ProbingThread("agc-test-plain-thread", false);
        plain.start();
        plain.join(5_000L);
        assertFalse(plain.result, "a plain thread must fail the guard");
    }

    @Test
    void virtualContextNeverGrantsTickThreadIdentity() throws InterruptedException {
        final ProbingThread virtualThread = new ProbingThread("agc-test-virtual-thread", true);
        virtualThread.start();
        virtualThread.join(5_000L);
        assertFalse(virtualThread.result,
            "a non-tick thread inside an AgcPluginVirtualizer context must still fail the guard - "
                + "fake thread identity is what allowed worker threads to mutate world state");
    }

    @Test
    void guardAnswersAreConsistentUnderConcurrentProbing() throws InterruptedException {
        final int readers = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(readers);
        final AtomicBoolean failure = new AtomicBoolean(false);
        for (int i = 0; i < readers; i++) {
            final ProbingThread prober = new ProbingThread("agc-test-concurrent-" + i, true) {
                @Override
                public void run() {
                    boolean ok = true;
                    try {
                        start.await();
                        // probe repeatedly: any true for a non-tick thread is a missed guard
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
            prober.start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(failure.get(), "guard results must be stable while many threads probe concurrently");
    }
}
