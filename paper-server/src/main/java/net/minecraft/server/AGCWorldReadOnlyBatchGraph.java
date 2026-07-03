package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** Compiles read-only world phase batches without moving visible barriers. */
public final class AGCWorldReadOnlyBatchGraph {
    public static final AGCWorldReadOnlyBatchGraph INSTANCE = new AGCWorldReadOnlyBatchGraph();

    private final AtomicLong compiled = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCWorldReadOnlyBatchGraph() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "reset";
    }

    public Graph compile(final AGCWorldWriteIntentGraph.Plan plan, final long translatorPending) {
        if (plan == null) {
            this.ordered.incrementAndGet();
            this.lastReason = "null world plan remains ordered";
            return new Graph(false, 0, 0, 0L, 0L, this.lastReason);
        }
        final int worlds = Math.max(1, plan.nodes().size());
        final int parallelGroups = Math.max(0, plan.parallelGroups());
        final int readOnlyBatches = Math.max(1, Math.min(2048, parallelGroups + Math.max(1, worlds / 2)));
        final int visibleBarriers = Math.max(1, worlds - parallelGroups + 1);
        final long cost = Math.max(1L, readOnlyBatches * 64L + Math.max(0L, translatorPending / 16L));
        if (translatorPending > 65_536L || visibleBarriers > readOnlyBatches * 8) {
            this.ordered.incrementAndGet();
            this.lastReason = "world graph keeps ordered visible barrier mode=" + plan.mode();
            return new Graph(false, readOnlyBatches, visibleBarriers, cost, stableKey(plan, translatorPending), this.lastReason);
        }
        this.compiled.incrementAndGet();
        this.lastReason = "compiled read-only world batch graph worlds=" + worlds + " batches=" + readOnlyBatches + " barriers=" + visibleBarriers;
        return new Graph(true, readOnlyBatches, visibleBarriers, cost, stableKey(plan, translatorPending), this.lastReason);
    }

    public String statusLine() {
        return "AGCWorldReadOnlyBatchGraph{tick=" + this.tickSequence
            + ", compiled=" + this.compiled.get()
            + ", ordered=" + this.ordered.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final AGCWorldWriteIntentGraph.Plan plan, final long translatorPending) {
        if (plan == null) {
            return translatorPending;
        }
        return ((long) plan.mode().ordinal() << 48) ^ ((long) plan.nodes().size() << 24) ^ plan.parallelGroups() ^ Long.rotateLeft(translatorPending, 7);
    }

    public record Graph(boolean readOnlyPrepare, int batches, int visibleBarriers, long cost, long stableKey, String reason) {}
}
