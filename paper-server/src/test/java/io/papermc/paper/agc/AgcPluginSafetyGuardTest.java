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
}
