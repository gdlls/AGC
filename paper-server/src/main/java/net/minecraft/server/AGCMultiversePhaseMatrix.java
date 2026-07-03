package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** Matrix that separates read-only multiverse preparation from ordered visible barriers. */
public final class AGCMultiversePhaseMatrix {
    public static final AGCMultiversePhaseMatrix INSTANCE = new AGCMultiversePhaseMatrix();

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong readOnlyWaves = new AtomicLong();
    private final AtomicLong orderedBarriers = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCMultiversePhaseMatrix() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "reset";
    }

    public Matrix compile(final AGCWorldWriteIntentGraph.Plan plan, final long translatorPending) {
        this.planned.incrementAndGet();
        final int worlds = plan == null ? 1 : Math.max(1, plan.nodes().size());
        final int groups = plan == null ? 0 : Math.max(0, plan.parallelGroups());
        final boolean readOnlyPrepare = translatorPending < 8192L && groups > 0;
        final int waves = readOnlyPrepare ? Math.max(1, Math.min(groups, worlds)) : 0;
        final int barriers = Math.max(1, worlds - waves + 1);
        if (readOnlyPrepare) {
            this.readOnlyWaves.addAndGet(waves);
        }
        this.orderedBarriers.addAndGet(barriers);
        this.lastReason = (readOnlyPrepare ? "compile read-only waves" : "ordered barrier only") + " worlds=" + worlds + " waves=" + waves + " barriers=" + barriers + " translatorPending=" + translatorPending;
        return new Matrix(readOnlyPrepare, worlds, waves, barriers, Math.max(1L, waves * 16L + barriers), this.lastReason);
    }

    public String statusLine() {
        return "AGCMultiversePhaseMatrix{tick=" + this.tickSequence
            + ", planned=" + this.planned.get()
            + ", readOnlyWaves=" + this.readOnlyWaves.get()
            + ", orderedBarriers=" + this.orderedBarriers.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    public record Matrix(boolean readOnlyPrepare, int worlds, int waves, int barriers, long cost, String reason) {}
}
