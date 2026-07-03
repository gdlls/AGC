package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** Records deadline compliance without changing gameplay behaviour. */
public final class AGCLatencySLO {
    public static final AGCLatencySLO INSTANCE = new AGCLatencySLO();

    private final AtomicLong deadlineBatches = new AtomicLong();
    private final AtomicLong deadlineDrains = new AtomicLong();
    private final AtomicLong deadlineViolations = new AtomicLong();
    private final AtomicLong orderedCommitDrains = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int maxNetworkDeadlineTicks = 1;
    private volatile int maxOrderedCommitTicks = 1;

    private AGCLatencySLO() {
    }

    public void configure(final boolean enabled, final int maxNetworkDeadlineTicks, final int maxOrderedCommitTicks) {
        this.enabled = enabled;
        this.maxNetworkDeadlineTicks = Math.max(0, maxNetworkDeadlineTicks);
        this.maxOrderedCommitTicks = Math.max(1, maxOrderedCommitTicks);
    }

    public void recordNetworkBatch(final int deadlineTicks) {
        this.deadlineBatches.incrementAndGet();
        if (this.enabled && deadlineTicks > this.maxNetworkDeadlineTicks) {
            this.deadlineViolations.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.NETWORK, AGCSemanticInvariant.Decision.CONFLICT_SERIALISED, "network deadline violation guarded");
        }
    }

    public void recordNetworkDrain() {
        this.deadlineDrains.incrementAndGet();
    }

    public void recordOrderedCommitDrain(final int ageTicks) {
        this.orderedCommitDrains.incrementAndGet();
        if (this.enabled && ageTicks > this.maxOrderedCommitTicks) {
            this.deadlineViolations.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.PLUGIN, AGCSemanticInvariant.Decision.CONFLICT_SERIALISED, "ordered commit deadline guarded");
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.maxNetworkDeadlineTicks, this.maxOrderedCommitTicks, this.deadlineBatches.get(), this.deadlineDrains.get(), this.orderedCommitDrains.get(), this.deadlineViolations.get());
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCLatencySLO{enabled=" + snapshot.enabled()
            + ", maxNetworkDeadlineTicks=" + snapshot.maxNetworkDeadlineTicks()
            + ", maxOrderedCommitTicks=" + snapshot.maxOrderedCommitTicks()
            + ", batches=" + snapshot.deadlineBatches()
            + ", drains=" + snapshot.deadlineDrains()
            + ", orderedCommitDrains=" + snapshot.orderedCommitDrains()
            + ", violations=" + snapshot.deadlineViolations()
            + '}';
    }

    public record Snapshot(boolean enabled, int maxNetworkDeadlineTicks, int maxOrderedCommitTicks, long deadlineBatches, long deadlineDrains, long orderedCommitDrains, long deadlineViolations) {
    }
}
