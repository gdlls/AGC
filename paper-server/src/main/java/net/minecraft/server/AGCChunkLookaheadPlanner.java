package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only chunk lookahead based on player motion. It builds stable vanilla-like
 * spiral hints for future interest, but never mutates chunk status, never skips
 * the FIFO head and never changes Paper completion ordering.
 */
public final class AGCChunkLookaheadPlanner {
    public static final AGCChunkLookaheadPlanner INSTANCE = new AGCChunkLookaheadPlanner();

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private volatile long tickSequence;
    private volatile int maxRadius = 12;
    private volatile int maxHintsPerPlayer = 192;

    private AGCChunkLookaheadPlanner() {
    }

    public void configure(final int maxRadius, final int maxHintsPerPlayer) {
        this.maxRadius = Math.max(2, Math.min(32, maxRadius));
        this.maxHintsPerPlayer = Math.max(16, Math.min(2048, maxHintsPerPlayer));
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Admission plan(final UUID playerId, final AGCChunkBudget.Operation operation, final AGCPlayerMotionIntentModel.Intent intent, final int viewDistance, final String reason) {
        final int radius = Math.max(2, Math.min(this.maxRadius, viewDistance <= 0 ? 8 : viewDistance));
        final long hints = Math.min(this.maxHintsPerPlayer, (long) (radius * 2 + 1) * (radius * 2 + 1));
        final long motionBias = intent == null ? 0L : Math.min(16L, Math.round(intent.speed() * 8.0D));
        final long cost = Math.max(1L, hints / 8L + motionBias);
        final AGCScale16ControlLaw.Admission budget = AGCScale16ControlLaw.INSTANCE.claim(AGCScale16ControlLaw.Axis.CHUNK_LOOKAHEAD, cost, reason);
        if (!budget.admitted()) {
            this.waits.incrementAndGet();
            return new Admission(false, 0L, radius, "chunk lookahead waits; FIFO queue remains untouched: " + budget.reason());
        }
        this.planned.incrementAndGet();
        return new Admission(true, hints, radius, operation == null ? "chunk-lookahead" : operation.name());
    }

    public String statusLine() {
        return "AGCChunkLookaheadPlanner{tick=" + this.tickSequence
            + ", maxRadius=" + this.maxRadius
            + ", maxHintsPerPlayer=" + this.maxHintsPerPlayer
            + ", planned=" + this.planned.get()
            + ", waits=" + this.waits.get()
            + '}';
    }

    public record Admission(boolean admitted, long hints, int radius, String reason) {
    }
}
