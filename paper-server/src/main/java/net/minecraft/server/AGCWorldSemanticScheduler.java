package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Compiles world work into semantic slots.
 *
 * Only read-only prepare slots may be widened. Block/fluid ticks, block entity
 * ticks, entity ticks, redstone, plugin events and ordered commits remain
 * serialized behind their vanilla/Paper-visible barriers.
 */
public final class AGCWorldSemanticScheduler {
    public static final AGCWorldSemanticScheduler INSTANCE = new AGCWorldSemanticScheduler();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong prepared = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastPrepareSlots;
    private volatile int lastOrderedBarriers;
    private volatile String lastReason = "cold";

    private AGCWorldSemanticScheduler() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "tick reset";
    }

    public Schedule compile(final AGCWorldWriteIntentGraph.Plan plan, final long translatorPending) {
        this.requested.incrementAndGet();
        final int groups = plan == null ? 0 : Math.max(0, plan.parallelGroups());
        final int prepareSlots = Math.max(1, Math.min(512, groups + 1));
        final int barriers = 5 + (translatorPending > 0L ? 1 : 0);
        final AGCScale20LosslessPipeline.Grant grant = AGCScale20LosslessPipeline.INSTANCE.claim(
            AGCScale20LosslessPipeline.Stage.WORLD_PLAN,
            Math.max(1L, prepareSlots * 8L + translatorPending / 512L),
            "world-semantic-scheduler"
        );
        this.lastPrepareSlots = prepareSlots;
        this.lastOrderedBarriers = barriers;
        if (!grant.admitted() || translatorPending > 16_384L) {
            this.ordered.incrementAndGet();
            this.lastReason = !grant.admitted() ? grant.reason() : "translator backlog preserves ordered barriers";
            return new Schedule(false, prepareSlots, barriers, this.lastReason);
        }
        this.prepared.incrementAndGet();
        this.lastReason = "compiled semantic schedule prepareSlots=" + prepareSlots + " orderedBarriers=" + barriers;
        return new Schedule(true, prepareSlots, barriers, this.lastReason);
    }

    public String statusLine() {
        return "AGCWorldSemanticScheduler{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", prepared=" + this.prepared.get()
            + ", ordered=" + this.ordered.get()
            + ", lastPrepareSlots=" + this.lastPrepareSlots
            + ", lastOrderedBarriers=" + this.lastOrderedBarriers
            + ", lastReason='" + this.lastReason + "'}";
    }

    public record Schedule(boolean prepareConcurrent, int prepareSlots, int orderedBarriers, String reason) {}
}
