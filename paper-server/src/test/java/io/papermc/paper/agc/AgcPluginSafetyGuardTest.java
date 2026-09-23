package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcPluginSafetyGuard}.
 */
class AgcPluginSafetyGuardTest {

    @BeforeEach
    @AfterEach
    void resetGuard() {
        AgcCapabilityMatrix.clearRuntimeOverrides();
        AgcPluginSafetyGuard.get().resetMetrics();
        AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
    }

    @Test
    void onPrimaryThreadExecutesSynchronously() {
        final AtomicBoolean executed = new AtomicBoolean(false);
        AgcPluginSafetyGuard.get().ensurePrimaryThread(() -> executed.set(true));

        assertTrue(executed.get());
        assertEquals(0, AgcPluginSafetyGuard.get().metrics().asyncCallsIntercepted());
    }

    @Test
    void supplyOnPrimaryThreadReturnsCompletedFutureWhenSynchronous() {
        final CompletableFuture<String> future = AgcPluginSafetyGuard.get().supplyOnPrimaryThread(() -> "result-data");
        assertTrue(future.isDone());
        assertEquals("result-data", future.join());
    }

    @Test
    void isPrimaryThreadIdentifiesBoundThread() {
        assertTrue(AgcPluginSafetyGuard.get().isPrimaryThread());

        final Thread otherThread = new Thread();
        AgcPluginSafetyGuard.get().bindPrimaryThread(otherThread);
        assertFalse(AgcPluginSafetyGuard.get().isPrimaryThread());
    }

    @Test
    void offPrimaryEnsureDefersToMailboxInsteadOfAsyncPool() {
        // Regression test for the fake-bridge bug: the previous implementation routed
        // off-primary "bridged" work through an async scheduler pool, which never moved
        // execution onto the primary thread. It must now be queued until drained on primary.
        final Thread otherThread = new Thread();
        AgcPluginSafetyGuard.get().bindPrimaryThread(otherThread);

        final AtomicBoolean executed = new AtomicBoolean(false);
        AgcPluginSafetyGuard.get().ensurePrimaryThread(() -> executed.set(true));

        assertFalse(executed.get(), "action must not execute inline off the primary thread");
        assertEquals(1, AgcPluginSafetyGuard.get().pendingCount());
        assertEquals(1, AgcPluginSafetyGuard.get().metrics().asyncCallsIntercepted());

        // Simulate the primary thread drain (wired in MinecraftServer#tickChildren).
        AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
        final int drained = AgcPluginSafetyGuard.get().drainMailbox(0);

        assertTrue(executed.get());
        assertEquals(1, drained);
        assertEquals(0, AgcPluginSafetyGuard.get().pendingCount());
        assertEquals(1, AgcPluginSafetyGuard.get().metrics().bridgesExecuted());
    }

    @Test
    void supplyOnPrimaryThreadCompletesAfterMailboxDrain() {
        final Thread otherThread = new Thread();
        AgcPluginSafetyGuard.get().bindPrimaryThread(otherThread);

        final CompletableFuture<String> future =
            AgcPluginSafetyGuard.get().supplyOnPrimaryThread(() -> "deferred-result");
        assertFalse(future.isDone(), "future must not complete before the primary thread drains");

        AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
        AgcPluginSafetyGuard.get().drainMailbox(0);

        assertTrue(future.isDone());
        assertEquals("deferred-result", future.join());
    }

    @Test
    void drainMailboxThrowsWhenCalledOffPrimary() {
        final Thread otherThread = new Thread();
        AgcPluginSafetyGuard.get().bindPrimaryThread(otherThread);

        assertThrows(IllegalStateException.class, () -> AgcPluginSafetyGuard.get().drainMailbox(0));
    }

    @Test
    void mailboxDrainsInFifoOrder() {
        final Thread otherThread = new Thread();
        AgcPluginSafetyGuard.get().bindPrimaryThread(otherThread);

        final java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final int id = i;
            AgcPluginSafetyGuard.get().ensurePrimaryThread(() -> order.add(id));
        }

        AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
        AgcPluginSafetyGuard.get().drainMailbox(0);

        assertEquals(java.util.List.of(0, 1, 2, 3, 4), order);
    }

    @Test
    void executeSynchronousPluginListenerOnPrimaryRunsImmediately() {
        final AtomicBoolean ran = new AtomicBoolean(false);
        AgcPluginSafetyGuard.get().executeSynchronousPluginListener("test-plugin", () -> {
            assertTrue(AgcPluginSafetyGuard.get().isPrimaryThread());
            ran.set(true);
        });

        assertTrue(ran.get());
        assertEquals(0, AgcPluginSafetyGuard.get().metrics().pluginInvocationsProtected());
    }

    @Test
    void executeSynchronousPluginListenerOffPrimaryStaysOffPrimaryAndSerializes() throws Exception {
        final Thread main = new Thread();
        AgcPluginSafetyGuard.get().bindPrimaryThread(main);

        final java.util.concurrent.atomic.AtomicInteger concurrentExecutions = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger maxConcurrency = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger totalRuns = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);

        final Runnable task = () -> {
            AgcPluginSafetyGuard.get().executeSynchronousPluginListener("test-plugin", () -> {
                // Honest contract: an off-primary dispatch stays visibly off-primary. It is
                // serialized (no concurrent world mutation) but it must NOT claim to be the
                // primary thread - that lie is what hid worker-thread plugin execution.
                assertFalse(AgcPluginSafetyGuard.get().isPrimaryThread());
                final int current = concurrentExecutions.incrementAndGet();
                maxConcurrency.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(20);
                } catch (final InterruptedException ignored) {}
                concurrentExecutions.decrementAndGet();
                totalRuns.incrementAndGet();
            });
            latch.countDown();
        };

        final Thread t1 = new Thread(task, "AGC-Worker-1");
        final Thread t2 = new Thread(task, "AGC-Worker-2");
        t1.start();
        t2.start();
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(2, totalRuns.get());
        assertEquals(1, maxConcurrency.get(), "Synchronous plugin listeners must be serialized mutually exclusively");
        assertEquals(2, AgcPluginSafetyGuard.get().metrics().pluginInvocationsProtected());
    }

    @Test
    void testNestedReentrantEventListenerDispatch() {
        final AtomicBoolean outerRan = new AtomicBoolean(false);
        final AtomicBoolean innerRan = new AtomicBoolean(false);

        AgcPluginSafetyGuard.get().executeSynchronousPluginListener("plugin-A", () -> {
            outerRan.set(true);
            assertTrue(AgcPluginSafetyGuard.get().isPrimaryThread());

            // Nested re-entrant invocation (e.g. event handler calling Bukkit.getPluginManager().callEvent)
            AgcPluginSafetyGuard.get().executeSynchronousPluginListener("plugin-B", () -> {
                innerRan.set(true);
                assertTrue(AgcPluginSafetyGuard.get().isPrimaryThread());
            });
        });

        assertTrue(outerRan.get());
        assertTrue(innerRan.get());
    }

    @Test
    void testWorldTickPhaseStaysOnPrimaryWhenPluginsMayBeLoaded() throws Exception {
        // Set up a world-tick scenario with 2 worlds
        final Thread primaryThread = Thread.currentThread();
        AgcPluginSafetyGuard.get().bindPrimaryThread(primaryThread);

        final java.util.concurrent.atomic.AtomicInteger offPrimaryRuns = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger totalRuns = new java.util.concurrent.atomic.AtomicInteger(0);

        // The engine refuses to tick worlds in parallel unless it can prove no plugin is loaded
        // (unit tests cannot inspect a plugin manager, so it fails safe to sequential - which is
        // exactly the production behaviour for plugin servers).
        AgcParallelWorldTickEngine.get().bootstrap();
        try {
            final AgcParallelWorldTickEngine.ExecutionSummary summary = AgcParallelWorldTickEngine.get().executeWorldTicks(
                java.util.List.of("world_1", "world_2"),
                world -> {
                    // Each world tick executes synchronous plugin listeners during the tick
                    for (int i = 0; i < 25; i++) {
                        AgcPluginSafetyGuard.get().executeSynchronousPluginListener("test-plugin", () -> {
                            if (!AgcPluginSafetyGuard.get().isPrimaryThread()) {
                                offPrimaryRuns.incrementAndGet();
                            }
                            totalRuns.incrementAndGet();
                        });
                    }
                },
                2
            );

            assertEquals(50, totalRuns.get());
            assertEquals(0, offPrimaryRuns.get(), "world ticks must stay on the primary thread when plugins may be present");
            assertFalse(summary.parallel(), "the plugin guard must force the sequential world-tick path");
        } finally {
            AgcParallelWorldTickEngine.get().shutdown();
            AgcCapabilityMatrix.clearRuntimeOverrides();
            AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
        }
    }
}
