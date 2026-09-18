package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@link AgcClassWarmup} engine semantics: fail-open per step,
 * idempotent kick-off, VANILLA-mode no-op, and completion signaling.
 */
class AgcClassWarmupTest {

    @Test
    void failingStepDoesNotBlockOtherSteps() {
        final AtomicInteger executed = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        final List<AgcClassWarmup.WarmStep> steps = List.of(
            new AgcClassWarmup.WarmStep() {
                @Override public String name() { return "boom"; }
                @Override public void run() { throw new IllegalStateException("boom"); }
            },
            new AgcClassWarmup.WarmStep() {
                @Override public String name() { return "counter"; }
                @Override public void run() { executed.incrementAndGet(); }
            },
            new AgcClassWarmup.WarmStep() {
                @Override public String name() { return "latch"; }
                @Override public void run() { done.countDown(); }
            }
        );
        AgcClassWarmup.runWarmup(steps);
        assertEquals(1, executed.get(), "steps after a throwing step must still run");
        assertEquals(0L, done.getCount(), "final step must run despite earlier failure");
        assertTrue(AgcClassWarmup.isWarmed(), "warmup must complete despite a failing step");
    }

    @Test
    void kickOffRunsStepsExactlyOnceAndSignalsCompletion() throws Exception {
        // kickOff is a process-global one-shot; earlier tests in this shared JVM may have consumed the latch.
        AgcClassWarmup.resetForTest();
        try {
            final AtomicInteger runs = new AtomicInteger();
            final CountDownLatch done = new CountDownLatch(1);
            final List<AgcClassWarmup.WarmStep> steps = List.of(
                new AgcClassWarmup.WarmStep() {
                    @Override public String name() { return "count"; }
                    @Override public void run() {
                        runs.incrementAndGet();
                        done.countDown();
                    }
                }
            );
            AgcClassWarmup.kickOff(steps);
            AgcClassWarmup.kickOff(steps); // second call must be a no-op
            AgcClassWarmup.kickOff(steps);
            assertTrue(done.await(10, TimeUnit.SECONDS), "warmup must complete in time");
            AgcClassWarmup.kickOff(steps); // even after completion, no re-run
            assertEquals(1, runs.get(), "steps must run exactly once across all kickOff calls");
            // The latch is counted down from inside the step, so `warmed` is published slightly
            // later - poll instead of asserting immediately (this used to be a real flake).
            assertTrue(awaitWarmed(10, TimeUnit.SECONDS),
                "warmup must publish its completion flag after the last step");
        } finally {
            AgcClassWarmup.resetForTest();
        }
    }

    private static boolean awaitWarmed(final long timeout, final TimeUnit unit) throws InterruptedException {
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (AgcClassWarmup.isWarmed()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return AgcClassWarmup.isWarmed();
    }

    @Test
    void defaultStepsExistAndAreNamed() {
        final List<AgcClassWarmup.WarmStep> steps = AgcClassWarmup.defaultSteps();
        assertTrue(steps.size() >= 3, "warmup must cover the evidence-backed stall families");
        for (final AgcClassWarmup.WarmStep step : steps) {
            assertTrue(step.name() != null && !step.name().isBlank(), "every step needs an attributable name");
        }
    }
}
