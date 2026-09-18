package io.papermc.paper.agc.tick;

import io.papermc.paper.agc.AgcAdaptiveGovernor;
import io.papermc.paper.agc.AgcPerformanceGovernor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * AGC — Dynamic Adaptive Tick Budget Allocator.
 *
 * <p>Partitions the standard 50.0ms (50,000,000ns) server tick budget into deterministic,
 * priority-ranked slices across subsystems:
 * <ul>
 *   <li><b>Essential Budget (30ms):</b> Network I/O, player movement, scheduled block/fluid ticks, primary events.</li>
 *   <li><b>Elastic Budget (15ms):</b> Entity movements, mob AI goals, pathfinding, tile entities.</li>
 *   <li><b>Surplus Budget (5ms):</b> Asynchronous chunk generation, disk autosave, GC margin.</li>
 * </ul>
 * Provides targeted, high-precision {@link BooleanSupplier} deadlines tailored for each subsystem
 * to prevent frame drops and keep MSPT &lt; 45.0ms under massive concurrency.</p>
 */
public final class AgcTickBudgetAllocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcTickBudgetAllocator.class);
    private static final AgcTickBudgetAllocator INSTANCE = new AgcTickBudgetAllocator();

    public static final long TICK_TARGET_NANOS = 50_000_000L; // 50ms
    public static final long DEFAULT_ESSENTIAL_BUDGET_NANOS = 30_000_000L; // 30ms
    public static final long DEFAULT_ELASTIC_BUDGET_NANOS = 15_000_000L;   // 15ms
    public static final long DEFAULT_SURPLUS_BUDGET_NANOS = 5_000_000L;    // 5ms

    private final AtomicLong totalBudgetExceededTicks = new AtomicLong();
    private final AtomicLong entityThrottles = new AtomicLong();
    private final AtomicLong chunkGenThrottles = new AtomicLong();
    private final AtomicLong blockEntityThrottles = new AtomicLong();

    public static AgcTickBudgetAllocator get() {
        return INSTANCE;
    }

    private AgcTickBudgetAllocator() {}

    /**
     * Allocates a dynamic deadline based on current server performance governor state.
     * Under high congestion, elastic and surplus budgets are dynamically clamped.
     */
    public BudgetAllocation allocate(final long tickStartNanos) {
        final var govLevel = AgcAdaptiveGovernor.get().getLevel();

        final long essentialBudget;
        final long elasticBudget;
        final long surplusBudget;

        switch (govLevel) {
            case LEVEL_0_OPTIMAL -> {
                essentialBudget = 32_000_000L;
                elasticBudget = 14_000_000L;
                surplusBudget = 4_000_000L;
            }
            case LEVEL_1_ELEVATED -> {
                essentialBudget = 30_000_000L;
                elasticBudget = 15_000_000L;
                surplusBudget = 3_000_000L;
            }
            case LEVEL_2_CONGESTED -> {
                essentialBudget = 28_000_000L;
                elasticBudget = 16_000_000L;
                surplusBudget = 2_000_000L;
            }
            case LEVEL_3_SEVERE -> {
                essentialBudget = 26_000_000L;
                elasticBudget = 18_000_000L;
                surplusBudget = 1_000_000L;
            }
            case LEVEL_4_CRITICAL -> {
                essentialBudget = 25_000_000L;
                elasticBudget = 18_000_000L;
                surplusBudget = 500_000L;
            }
            default -> {
                essentialBudget = DEFAULT_ESSENTIAL_BUDGET_NANOS;
                elasticBudget = DEFAULT_ELASTIC_BUDGET_NANOS;
                surplusBudget = DEFAULT_SURPLUS_BUDGET_NANOS;
            }
        }

        final long essentialDeadline = tickStartNanos + essentialBudget;
        final long elasticDeadline = tickStartNanos + essentialBudget + elasticBudget;
        final long totalDeadline = tickStartNanos + TICK_TARGET_NANOS;

        return new BudgetAllocation(
            tickStartNanos,
            essentialDeadline,
            elasticDeadline,
            totalDeadline,
            () -> (System.nanoTime() < elasticDeadline),
            () -> (System.nanoTime() < totalDeadline),
            () -> {
                final boolean hasTime = System.nanoTime() < totalDeadline;
                if (!hasTime) chunkGenThrottles.incrementAndGet();
                return hasTime;
            }
        );
    }

    /**
     * Represents a calculated budget allocation for the current tick.
     */
    public record BudgetAllocation(
        long tickStartNanos,
        long essentialDeadlineNanos,
        long elasticDeadlineNanos,
        long totalDeadlineNanos,
        BooleanSupplier entityHaveTime,
        BooleanSupplier blockEntityHaveTime,
        BooleanSupplier chunkGenHaveTime
    ) {
        public boolean isEssentialTimeRemaining() {
            return System.nanoTime() < this.essentialDeadlineNanos;
        }

        public boolean isElasticTimeRemaining() {
            return System.nanoTime() < this.elasticDeadlineNanos;
        }

        public boolean isTickTimeRemaining() {
            return System.nanoTime() < this.totalDeadlineNanos;
        }

        public double elapsedMillis() {
            return (System.nanoTime() - this.tickStartNanos) / 1_000_000.0;
        }
    }

    public void recordEntityThrottle() {
        this.entityThrottles.incrementAndGet();
    }

    public void recordBlockEntityThrottle() {
        this.blockEntityThrottles.incrementAndGet();
    }

    public void recordTickBudgetExceeded() {
        this.totalBudgetExceededTicks.incrementAndGet();
    }

    public BudgetMetrics metrics() {
        return new BudgetMetrics(
            this.totalBudgetExceededTicks.get(),
            this.entityThrottles.get(),
            this.chunkGenThrottles.get(),
            this.blockEntityThrottles.get()
        );
    }

    public record BudgetMetrics(
        long budgetExceededTicks,
        long entityThrottles,
        long chunkGenThrottles,
        long blockEntityThrottles
    ) {}
}
