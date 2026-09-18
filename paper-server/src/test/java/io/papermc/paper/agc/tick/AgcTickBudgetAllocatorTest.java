package io.papermc.paper.agc.tick;

import io.agcmc.agc.api.event.AgcPerformanceLevelEvent;
import io.papermc.paper.agc.AgcAdaptiveGovernor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcTickBudgetAllocator}.
 */
public class AgcTickBudgetAllocatorTest {

    @BeforeEach
    @AfterEach
    void reset() {
        AgcAdaptiveGovernor.get().setManualOverride(null);
    }

    @Test
    void verifiesDynamicBudgetAllocationAndDeadlines() {
        final AgcTickBudgetAllocator allocator = AgcTickBudgetAllocator.get();
        // 1. Optimal level allocation
        AgcAdaptiveGovernor.get().setManualOverride(AgcPerformanceLevelEvent.PerformanceLevel.LEVEL_0_OPTIMAL);
        final long start = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        final var alloc0 = allocator.allocate(start);
        assertTrue(alloc0.essentialDeadlineNanos() > start);
        assertTrue(alloc0.elasticDeadlineNanos() > alloc0.essentialDeadlineNanos());
        assertTrue(alloc0.totalDeadlineNanos() >= alloc0.elasticDeadlineNanos());
        assertTrue(alloc0.isEssentialTimeRemaining());
        assertTrue(alloc0.isElasticTimeRemaining());
        assertTrue(alloc0.isTickTimeRemaining());
        assertTrue(alloc0.entityHaveTime().getAsBoolean());
        assertTrue(alloc0.blockEntityHaveTime().getAsBoolean());
        assertTrue(alloc0.chunkGenHaveTime().getAsBoolean());

        // Also verify expired deadline behavior
        final var allocExpired = allocator.allocate(System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(100));
        assertFalse(allocExpired.isEssentialTimeRemaining());
        assertFalse(allocExpired.isElasticTimeRemaining());
        assertFalse(allocExpired.isTickTimeRemaining());
        assertFalse(allocExpired.entityHaveTime().getAsBoolean());
        assertFalse(allocExpired.blockEntityHaveTime().getAsBoolean());
        assertFalse(allocExpired.chunkGenHaveTime().getAsBoolean());

        // 2. Critical level allocation clamps surplus and elastic
        AgcAdaptiveGovernor.get().setManualOverride(AgcPerformanceLevelEvent.PerformanceLevel.LEVEL_4_CRITICAL);
        final var alloc4 = allocator.allocate(start);
        assertNotNull(alloc4);
        assertNotNull(alloc4);

        // 3. Metrics tracking
        allocator.recordEntityThrottle();
        allocator.recordBlockEntityThrottle();
        allocator.recordTickBudgetExceeded();

        final var metrics = allocator.metrics();
        assertTrue(metrics.entityThrottles() >= 1);
        assertTrue(metrics.blockEntityThrottles() >= 1);
        assertTrue(metrics.budgetExceededTicks() >= 1);
    }
}
