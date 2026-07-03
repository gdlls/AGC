package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** Read-only world barrier DAG compiler for alpha23. */
public final class AGCWorldBarrierDAGCompiler {
    public static final AGCWorldBarrierDAGCompiler INSTANCE = new AGCWorldBarrierDAGCompiler();

    private final AtomicLong compiled = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCWorldBarrierDAGCompiler() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "reset";
    }

    public Plan compile(final AGCWorldWriteIntentGraph.Plan plan, final long translatorPending) {
        if (plan == null) {
            this.ordered.incrementAndGet();
            this.lastReason = "missing world plan remains ordered";
            return new Plan(false, 0, 1, 1L, this.lastReason);
        }
        final int worlds = Math.max(1, plan.nodes().size());
        final int groups = Math.max(0, plan.parallelGroups());
        final int readOnlyWaves = Math.max(0, Math.min(worlds, groups));
        final int orderedBarriers = Math.max(1, worlds - readOnlyWaves + (translatorPending > 0L ? 1 : 0));
        final long cost = Math.max(1L, (long) worlds * Math.max(1, readOnlyWaves + orderedBarriers));
        if (translatorPending > 32_768L || readOnlyWaves == 0) {
            this.ordered.incrementAndGet();
            this.lastReason = "world barrier DAG keeps ordered visible barriers mode=" + plan.mode();
            return new Plan(false, readOnlyWaves, orderedBarriers, cost, this.lastReason);
        }
        this.compiled.incrementAndGet();
        this.lastReason = "compiled read-only world barrier DAG waves=" + readOnlyWaves + " barriers=" + orderedBarriers;
        return new Plan(true, readOnlyWaves, orderedBarriers, cost, this.lastReason);
    }

    public String statusLine() {
        return "AGCWorldBarrierDAGCompiler{tick=" + this.tickSequence
            + ", compiled=" + this.compiled.get()
            + ", ordered=" + this.ordered.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    public record Plan(boolean readOnlyPrepare, int readOnlyWaves, int orderedBarriers, long cost, String reason) {}
}
