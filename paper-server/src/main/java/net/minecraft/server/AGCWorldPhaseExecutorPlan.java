package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Compiles the hard-coded Minecraft phase DAG into prepare waves and ordered
 * barriers. It only schedules read-only prepare phases; redstone, block entity,
 * entity tick, plugin event and commit phases remain ordered barriers.
 */
public final class AGCWorldPhaseExecutorPlan {
    public static final AGCWorldPhaseExecutorPlan INSTANCE = new AGCWorldPhaseExecutorPlan();

    private final AtomicLong compiled = new AtomicLong();
    private final AtomicLong orderedBarriers = new AtomicLong();
    private volatile long tickSequence;
    private volatile int maxPrepareWaves = 64;

    private AGCWorldPhaseExecutorPlan() {
    }

    public void configure(final int maxPrepareWaves) {
        this.maxPrepareWaves = Math.max(1, Math.min(1024, maxPrepareWaves));
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Plan compile(final AGCWorldWriteIntentGraph.Plan plan, final long translatorPending) {
        if (plan == null) {
            this.orderedBarriers.incrementAndGet();
            return new Plan(false, 0, 1, "no world plan; ordered barrier");
        }
        final int requested = Math.max(0, plan.parallelGroups());
        if (requested <= 0 || translatorPending > 0L && translatorPending > requested * 1024L) {
            this.orderedBarriers.incrementAndGet();
            return new Plan(false, 0, 1, "world phase compiler keeps ordered barrier under translator pressure");
        }
        final int prepareWaves = Math.min(this.maxPrepareWaves, requested);
        final long cost = Math.max(1L, prepareWaves + translatorPending / 2048L);
        final AGCScale17AlgorithmKernel.Admission budget = AGCScale17AlgorithmKernel.INSTANCE.claim(
            AGCScale17AlgorithmKernel.Plane.WORLD_PHASE_COMPILER,
            cost,
            plan.mode().name()
        );
        if (!budget.admitted()) {
            this.orderedBarriers.incrementAndGet();
            return new Plan(false, 0, 1, "phase compiler waits; ordered barrier remains: " + budget.reason());
        }
        this.compiled.incrementAndGet();
        return new Plan(true, prepareWaves, 1, "read-only prepare waves + single ordered commit barrier");
    }

    public String statusLine() {
        return "AGCWorldPhaseExecutorPlan{tick=" + this.tickSequence
            + ", maxPrepareWaves=" + this.maxPrepareWaves
            + ", compiled=" + this.compiled.get()
            + ", orderedBarriers=" + this.orderedBarriers.get()
            + '}';
    }

    public record Plan(boolean prepareConcurrent, int prepareWaves, int orderedBarriers, String reason) {
    }
}
