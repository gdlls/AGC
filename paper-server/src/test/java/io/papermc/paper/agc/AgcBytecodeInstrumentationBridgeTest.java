package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcBytecodeInstrumentationBridge} after the fake primary-thread identity was
 * removed.
 *
 * <p>The bridge routes plugin-facing calls: on the real primary thread it runs them inline, and
 * anywhere else it defers them through {@link AgcPluginSafetyGuard#ensurePrimaryThread} instead of
 * pretending the caller was already on the primary thread.</p>
 */
public class AgcBytecodeInstrumentationBridgeTest {

    @BeforeEach
    void setUp() {
        AgcBytecodeInstrumentationBridge.get().resetMetrics();
        AgcPluginVirtualizer.get().resetMetrics();
        AgcPluginSafetyGuard.get().resetMetrics();
        AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
    }

    @Test
    void testRouteSchedulerTaskRunsInlineOnPrimaryThread() {
        final AtomicBoolean executed = new AtomicBoolean(false);

        AgcBytecodeInstrumentationBridge.get().routeSchedulerTask("TestPlugin", () -> executed.set(true));

        assertTrue(executed.get(), "on the primary thread the task runs inline");
        final AgcBytecodeInstrumentationBridge.BridgeMetrics metrics = AgcBytecodeInstrumentationBridge.get().metrics();
        assertEquals(1, metrics.schedulerCallsRouted());
        assertEquals(1, metrics.directFastPathExecutions());
    }

    @Test
    void testRouteSchedulerTaskDefersOffPrimaryInsteadOfFakingIdentity() throws Exception {
        final Thread primary = new Thread(() -> {}, "dummy-primary");
        AgcPluginSafetyGuard.get().bindPrimaryThread(primary);

        final AtomicBoolean executedWhileOffPrimary = new AtomicBoolean(false);
        final AtomicBoolean executedOnPrimary = new AtomicBoolean(false);

        final Thread worker = new Thread(() -> {
            AgcBytecodeInstrumentationBridge.get().routeSchedulerTask("TestPlugin", () -> {
                if (AgcPluginSafetyGuard.get().isPrimaryThread()) {
                    executedOnPrimary.set(true);
                } else {
                    executedWhileOffPrimary.set(true);
                }
            });
        }, "agc-test-worker");
        worker.start();
        worker.join(5_000L);

        assertFalse(executedWhileOffPrimary.get(),
            "the task must never run on the worker thread with a faked primary identity");
        assertTrue(AgcPluginSafetyGuard.get().pendingCount() > 0,
            "off-primary scheduler routing must land in the primary-thread mailbox");

        AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
        AgcPluginSafetyGuard.get().drainMailbox(Integer.MAX_VALUE);
        assertTrue(executedOnPrimary.get(), "draining on the real primary thread must run the task there");
    }

    @Test
    void testRouteEventExecutionNeverClaimsPrimaryOffThread() throws Exception {
        final Thread primary = new Thread(() -> {}, "dummy-primary");
        AgcPluginSafetyGuard.get().bindPrimaryThread(primary);

        final AtomicInteger offPrimaryRuns = new AtomicInteger();
        final AtomicInteger primaryRuns = new AtomicInteger();

        final Thread worker = new Thread(() -> AgcBytecodeInstrumentationBridge.get().routeEventExecution(
            "world", 100L, () -> {
                if (AgcPluginSafetyGuard.get().isPrimaryThread()) {
                    primaryRuns.incrementAndGet();
                } else {
                    offPrimaryRuns.incrementAndGet();
                }
            }), "agc-test-worker");
        worker.start();
        worker.join(5_000L);

        assertEquals(0, offPrimaryRuns.get(), "no listener body may execute off-primary");
        assertTrue(AgcPluginSafetyGuard.get().pendingCount() > 0);

        AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
        AgcPluginSafetyGuard.get().drainMailbox(Integer.MAX_VALUE);
        assertEquals(1, primaryRuns.get(), "the listener body must run on the real primary thread");
    }
}
