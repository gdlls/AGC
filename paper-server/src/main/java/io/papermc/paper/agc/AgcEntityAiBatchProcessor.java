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

    private final AtomicLong goalsEvaluated = new AtomicLong();
    private final AtomicLong goalsSkipped = new AtomicLong();
    private final AtomicLong sensorsTicked = new AtomicLong();
    private final AtomicLong sensorsSkipped = new AtomicLong();

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

        // Idle mobs evaluate round-robin across BUCKET_COUNT ticks using fast bitwise mask
        final int bucket = entityId & (BUCKET_COUNT - 1);
        final boolean shouldRun = ((int) currentTick & (BUCKET_COUNT - 1)) == bucket;

        if (shouldRun) {
            this.goalsEvaluated.incrementAndGet();
        } else {
            this.goalsSkipped.incrementAndGet();
        }
        return shouldRun;
    }

    /**
     * Determines whether an entity's Brain sensors should tick on this tick.
     * Inactive / idle entities throttle sensor evaluation (every 2 ticks round-robin),
     * saving up to 50% Brain sensor tick overhead (Pufferfish DAB / Petal).
     *
     * @param entityId    Entity ID
     * @param currentTick Current world tick
     * @param isEngaged   Whether the entity has active targets or behaviors
     * @return true if sensors should tick
     */
    public boolean shouldTickSensors(final int entityId, final long currentTick, final boolean isEngaged) {
        if (isEngaged) {
            this.sensorsTicked.incrementAndGet();
            return true;
        }

        final boolean run = ((currentTick + entityId) & 1) == 0;
        if (run) {
            this.sensorsTicked.incrementAndGet();
        } else {
            this.sensorsSkipped.incrementAndGet();
        }
        return run;
    }

    /**
     * Resets AI batching telemetry counters.
     */
    public void resetMetrics() {
        this.goalsEvaluated.set(0);
        this.goalsSkipped.set(0);
        this.sensorsTicked.set(0);
        this.sensorsSkipped.set(0);
    }

    public long getSensorsTicked() {
        return this.sensorsTicked.get();
    }

    public long getSensorsSkipped() {
        return this.sensorsSkipped.get();
    }

    public AiMetrics metrics() {
        return new AiMetrics(
            this.goalsEvaluated.get(),
            this.goalsSkipped.get(),
            this.sensorsTicked.get(),
            this.sensorsSkipped.get()
        );
    }

    public record AiMetrics(
        long goalsEvaluated,
        long goalsSkipped,
        long sensorsTicked,
        long sensorsSkipped
    ) {
        public long totalGoalRequests() {
            return this.goalsEvaluated + this.goalsSkipped;
        }

        public long totalSensorRequests() {
            return this.sensorsTicked + this.sensorsSkipped;
        }

        public double goalCpuReductionRatio() {
            final long total = totalGoalRequests();
            if (total == 0) return 0.0;
            return (double) this.goalsSkipped / (double) total;
        }

        public double sensorCpuReductionRatio() {
            final long total = totalSensorRequests();
            if (total == 0) return 0.0;
            return (double) this.sensorsSkipped / (double) total;
        }

        public long totalRequests() {
            return this.goalsEvaluated + this.goalsSkipped;
        }

        public double cpuReductionRatio() {
            return goalCpuReductionRatio();
        }
    }
}
