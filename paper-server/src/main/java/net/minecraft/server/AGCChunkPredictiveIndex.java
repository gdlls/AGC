package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Motion-aware chunk hint index for alpha17.
 * <p>
 * It produces stable lookahead rings for future chunk interest. The result is a
 * hint only: it never skips a FIFO queue head, never starts generation out of
 * Paper order and never changes redstone/block-entity semantics.
 */
public final class AGCChunkPredictiveIndex {
    public static final AGCChunkPredictiveIndex INSTANCE = new AGCChunkPredictiveIndex();

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private final AtomicLong stableSpiralHints = new AtomicLong();
    private volatile long tickSequence;
    private volatile int maxRingRadius = 16;
    private volatile int maxHints = 384;

    private AGCChunkPredictiveIndex() {
    }

    public void configure(final int maxRingRadius, final int maxHints) {
        this.maxRingRadius = Math.max(2, Math.min(32, maxRingRadius));
        this.maxHints = Math.max(16, Math.min(4096, maxHints));
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Plan plan(final UUID playerId, final AGCChunkBudget.Operation operation, final AGCPlayerMotionIntentModel.Intent intent, final int currentViewDistance, final int backlog) {
        final double speed = intent == null ? 0.0D : Math.max(0.0D, intent.speed());
        final int radius = Math.max(2, Math.min(this.maxRingRadius, currentViewDistance <= 0 ? 10 : currentViewDistance));
        final int forwardBias = Math.max(0, Math.min(6, (int) Math.ceil(speed * 2.0D)));
        final int effectiveRadius = Math.min(this.maxRingRadius, radius + forwardBias);
        final long ringArea = (long) (effectiveRadius * 2 + 1) * (long) (effectiveRadius * 2 + 1);
        final long boundedHints = Math.min(this.maxHints, ringArea);
        final long cost = Math.max(1L, boundedHints / 6L + Math.max(0, backlog) / 32L + forwardBias);
        final AGCScale17AlgorithmKernel.Admission admission = AGCScale17AlgorithmKernel.INSTANCE.claim(
            AGCScale17AlgorithmKernel.Plane.CHUNK_PREDICTIVE_INDEX,
            cost,
            operation == null ? "chunk predictive index" : operation.name()
        );
        if (!admission.admitted()) {
            this.waits.incrementAndGet();
            return new Plan(false, effectiveRadius, 0L, forwardBias, "predictive chunk hints wait; FIFO order is unchanged: " + admission.reason());
        }
        this.planned.incrementAndGet();
        this.stableSpiralHints.addAndGet(boundedHints);
        return new Plan(true, effectiveRadius, boundedHints, forwardBias, "stable-spiral-lookahead");
    }

    public String statusLine() {
        return "AGCChunkPredictiveIndex{tick=" + this.tickSequence
            + ", maxRingRadius=" + this.maxRingRadius
            + ", maxHints=" + this.maxHints
            + ", planned=" + this.planned.get()
            + ", waits=" + this.waits.get()
            + ", stableSpiralHints=" + this.stableSpiralHints.get()
            + '}';
    }

    public record Plan(boolean admitted, int effectiveRadius, long hints, int forwardBias, String reason) {
    }
}
