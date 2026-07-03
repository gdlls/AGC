package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Compiles read-only world phase preparation into deterministic batches.
 * Visible phases are not batched here: block/fluid ticks, block entities,
 * entity ticks, plugin events and ordered commits keep their vanilla/Paper order.
 */
public final class AGCWorldPhaseBatchCompiler {
    public static final AGCWorldPhaseBatchCompiler INSTANCE = new AGCWorldPhaseBatchCompiler();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong compiled = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastBatches;
    private volatile int lastOrderedBarriers;
    private volatile String lastReason = "cold";

    private AGCWorldPhaseBatchCompiler() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "tick reset";
    }

    public Batch compile(final AGCWorldWriteIntentGraph.Plan plan, final long translatorPending) {
        this.requested.incrementAndGet();
        if (plan == null) {
            this.ordered.incrementAndGet();
            this.lastReason = "no world plan supplied";
            return new Batch(false, 0, 1, this.lastReason);
        }
        final int groups = Math.max(0, plan.parallelGroups());
        if (groups <= 0 || translatorPending > 8192L) {
            this.ordered.incrementAndGet();
            this.lastBatches = 0;
            this.lastOrderedBarriers = 1;
            this.lastReason = "ordered barrier required groups=" + groups + " translatorPending=" + translatorPending;
            return new Batch(false, 0, 1, this.lastReason);
        }
        final int batches = Math.max(1, Math.min(256, groups));
        final int orderedBarriers = 3 + Math.min(8, batches / 8);
        final AGCScale19LogicKernel.Grant grant = AGCScale19LogicKernel.INSTANCE.claim(
            AGCScale19LogicKernel.Axis.WORLD_PHASE_BATCH,
            Math.max(1L, batches * 4L + translatorPending / 512L),
            "world-phase-batch " + plan.mode()
        );
        this.lastBatches = batches;
        this.lastOrderedBarriers = orderedBarriers;
        if (!grant.admitted()) {
            this.ordered.incrementAndGet();
            this.lastReason = grant.reason();
            return new Batch(false, batches, orderedBarriers, this.lastReason);
        }
        this.compiled.incrementAndGet();
        this.lastReason = "compiled read-only phase batches=" + batches + " orderedBarriers=" + orderedBarriers;
        return new Batch(true, batches, orderedBarriers, this.lastReason);
    }

    public String statusLine() {
        return "AGCWorldPhaseBatchCompiler{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", compiled=" + this.compiled.get()
            + ", ordered=" + this.ordered.get()
            + ", lastBatches=" + this.lastBatches
            + ", lastOrderedBarriers=" + this.lastOrderedBarriers
            + ", lastReason='" + this.lastReason + "'}";
    }

    public record Batch(boolean readOnlyBatches, int batches, int orderedBarriers, String reason) {}
}
