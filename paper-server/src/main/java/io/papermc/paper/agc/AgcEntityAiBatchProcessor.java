package io.papermc.paper.agc;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Entity AI Goal & Pathfinding Batch Processor.
 *
 * <p>Standard Minecraft mob pathfinding triggers expensive {@code GoalSelector#canUse()}
 * and raycast/spatial searches on every single mob every tick. When hundreds of mobs are spawned
 * across multiple worlds, AI goal evaluation becomes a major CPU bottleneck.</p>
 *
 * <p>This processor distributes goal evaluation across a 4-phase round-robin batch schedule
 * based on entity ID modulus, ensuring that idle mobs only evaluate new targets every 4 ticks
 * while active behaviors (e.g. actively attacking, fleeing, eating) execute continuously.</p>
 */
public final class AgcEntityAiBatchProcessor {

    private static final AgcEntityAiBatchProcessor INSTANCE = new AgcEntityAiBatchProcessor();
    public static final int BUCKET_COUNT = 4;

    // Telemetry & metrics
    private final AtomicLong goalsEvaluated = new AtomicLong();
    private final AtomicLong goalsSkipped = new AtomicLong();

    public static AgcEntityAiBatchProcessor get() {
        return INSTANCE;
    }

    private AgcEntityAiBatchProcessor() {}

    /**
     * Determines whether an idle entity should re-evaluate its AI goals on the current tick.
     *
     * @param entityId    Entity ID
     * @param currentTick Current world tick
     * @param isEngaged   Whether the entity is already actively pursuing a target or executing a critical goal
     * @return true if AI goals should be evaluated on this tick
     */
    public boolean shouldEvaluateAi(final int entityId, final long currentTick, final boolean isEngaged) {
        if (isEngaged) {
            // Actively engaged mobs (e.g. in combat or active pathing) evaluate every tick for responsiveness
            this.goalsEvaluated.incrementAndGet();
            return true;
        }

        // Idle mobs evaluate round-robin across BUCKET_COUNT ticks
        final int bucket = Math.abs(entityId % BUCKET_COUNT);
        final boolean shouldRun = (currentTick % BUCKET_COUNT) == bucket;

        if (shouldRun) {
            this.goalsEvaluated.incrementAndGet();
        } else {
            this.goalsSkipped.incrementAndGet();
        }
        return shouldRun;
    }

    /**
     * Resets AI batching telemetry counters.
     */
    public void resetMetrics() {
        this.goalsEvaluated.set(0);
        this.goalsSkipped.set(0);
    }

    public AiMetrics metrics() {
        return new AiMetrics(
            this.goalsEvaluated.get(),
            this.goalsSkipped.get()
        );
    }

    public record AiMetrics(
        long goalsEvaluated,
        long goalsSkipped
    ) {
        public long totalRequests() {
            return this.goalsEvaluated + this.goalsSkipped;
        }

        public double cpuReductionRatio() {
            final long total = totalRequests();
            if (total == 0) return 0.0;
            return (double) this.goalsSkipped / (double) total;
        }
    }
}
