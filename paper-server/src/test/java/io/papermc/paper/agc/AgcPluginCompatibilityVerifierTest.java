package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcPluginCompatibilityVerifierTest {

    @BeforeEach
    @AfterEach
    void cleanState() {
        AgcCapabilityMatrix.clearRuntimeOverrides();
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        AgcPluginVirtualizer.get().resetMetrics();
        AgcOptimisticTransactionManager.get().clear();
        AgcCrossWorldQueue.get().resetMetrics();
        AgcPluginSafetyGuard.get().resetMetrics();
        AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
    }

    @Test
    void testFiftyPluginConcurrencyCompatibilitySimulation() {
        // Simulate 50 plugins executing 100 concurrent ops each across 8 worker threads
        final AgcPluginCompatibilityVerifier.VerificationReport report =
            AgcPluginCompatibilityVerifier.get().runCompatibilitySuite(50, 100, 8);

        assertNotNull(report);
        assertTrue(report.passed(), "Plugin compatibility simulation must pass with zero errors");
        assertEquals(0, report.concurrencyErrors(), "Must have zero concurrency errors");
        assertEquals(50 * 100, report.syncPrimaryAssertionsPassed());
        assertTrue(report.totalOpsExecuted() > 0);
        assertTrue(report.elapsedMs() < 5000.0, "Simulation must complete well within timeout");

        System.out.println("Plugin Compatibility 50-Plugin Simulation Time: " + report.elapsedMs() + "ms");
    }

    @Test
    void testSynchronousPluginEventListenerSerializationUnderConcurrentWorkers() throws Exception {
        final int pluginCount = 20;
        final int eventsPerWorker = 50;
        final int workerCount = 8;
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workerCount);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(workerCount);
        final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<Integer> nonThreadSafeList = new java.util.ArrayList<>();

        // Bind off-thread so executeSynchronousPluginListener engages guard serialization
        final Thread primary = new Thread();
        AgcPluginSafetyGuard.get().bindPrimaryThread(primary);

        try {
            for (int w = 0; w < workerCount; w++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < eventsPerWorker; i++) {
                            final int val = i;
                            final String pluginName = "Plugin_" + (val % pluginCount);
                            AgcPluginSafetyGuard.get().executeSynchronousPluginListener(pluginName, () -> {
                                if (!AgcPluginSafetyGuard.get().isPrimaryThread()) {
                                    errors.incrementAndGet();
                                }
                                nonThreadSafeList.add(val);
                            });
                        }
                    } catch (final Throwable t) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
            pool.shutdownNow();

            assertEquals(0, errors.get(), "No errors or concurrency violations should occur");
            assertEquals(workerCount * eventsPerWorker, nonThreadSafeList.size(), "All listener executions must complete safely into the non-thread-safe collection");
            assertEquals(workerCount * eventsPerWorker, AgcPluginSafetyGuard.get().metrics().pluginInvocationsProtected());
        } finally {
            AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
        }
    }

    @Test
    void testPrimaryThreadAndWorkersConcurrentExecutionUnderParallelTick() throws Exception {
        final int workerCount = 6;
        final int eventsPerThread = 40;
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workerCount);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(workerCount);
        final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<String> nonThreadSafeLog = new java.util.ArrayList<>();

        // Primary thread is current thread
        final Thread primary = Thread.currentThread();
        AgcPluginSafetyGuard.get().bindPrimaryThread(primary);
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        AgcParallelWorldTickEngine.get().bootstrap();

        try {
            // Simulate parallel world ticking wave
            final java.util.List<String> worlds = new java.util.ArrayList<>();
            for (int i = 0; i < workerCount + 1; i++) {
                worlds.add("parallel_world_" + i);
            }

            AgcParallelWorldTickEngine.get().executeWorldTicks(
                worlds,
                world -> {
                    // Running across both primary thread (wave[0]) and worker threads (wave[1..N])
                    try {
                        for (int i = 0; i < eventsPerThread; i++) {
                            final String msg = world + ":" + i;
                            AgcPluginSafetyGuard.get().executeSynchronousPluginListener("test-plugin", () -> {
                                if (!AgcPluginSafetyGuard.get().isPrimaryThread()) {
                                    errors.incrementAndGet();
                                }
                                nonThreadSafeLog.add(msg);
                            });
                        }
                    } catch (final Throwable t) {
                        errors.incrementAndGet();
                    }
                },
                2
            );

            pool.shutdownNow();

            assertEquals(0, errors.get(), "Zero concurrency errors under simultaneous primary + worker listener dispatches");
            final int expectedTotal = (workerCount + 1) * eventsPerThread;
            assertEquals(expectedTotal, nonThreadSafeLog.size(), "All operations must land in non-thread-safe collection without data loss or corruption");
            assertTrue(AgcPluginSafetyGuard.get().metrics().pluginInvocationsProtected() > 0, "Guard must protect invocations during parallel phase");
        } finally {
            AgcParallelWorldTickEngine.get().shutdown();
            AgcCapabilityMatrix.clearRuntimeOverrides();
            AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
            AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
        }
    }

    @Test
    void testPaperPluginCompatibilityGuarantees() {
        assertFalse(AgcParallelWorldTickEngine.get().isParallelPhaseActive(),
            "Standard server operation must not have parallel world ticking active");
        assertTrue(AgcPluginSafetyGuard.get().isPrimaryThread(),
            "Primary thread must be recognized as primary thread");

        final java.util.concurrent.atomic.AtomicBoolean executed = new java.util.concurrent.atomic.AtomicBoolean(false);
        AgcPluginSafetyGuard.get().executeSynchronousPluginListener("test-plugin", () -> executed.set(true));
        assertTrue(executed.get(), "Synchronous listener must execute immediately");
    }
}
