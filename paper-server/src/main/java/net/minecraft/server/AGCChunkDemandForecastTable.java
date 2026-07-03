package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stable next-chunk demand forecaster for survival scale.
 *
 * The forecast is only a hint table. It never starts a chunk load by itself,
 * never skips a FIFO head and never changes Paper's chunk completion order.
 */
public final class AGCChunkDemandForecastTable {
    public static final AGCChunkDemandForecastTable INSTANCE = new AGCChunkDemandForecastTable();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong forecasted = new AtomicLong();
    private final AtomicLong fifoWait = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastRadius;
    private volatile int lastHints;
    private volatile long lastStableKey;
    private volatile String lastReason = "cold";

    private AGCChunkDemandForecastTable() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "tick reset";
    }

    public Forecast forecast(
        final UUID playerId,
        final AGCChunkBudget.Operation operation,
        final AGCPlayerMotionIntentModel.Intent motionIntent,
        final int baseRadius,
        final long queueDepth,
        final String reason
    ) {
        this.requested.incrementAndGet();
        final int safeBase = Math.max(1, baseRadius);
        final long speedBias = motionIntent == null ? 1L : Math.max(1L, Math.round(motionIntent.speed() * 4.0D));
        final int radius = Math.max(safeBase, Math.min(32, safeBase + (int) Math.min(4L, speedBias)));
        final int hints = Math.max(8, Math.min(4096, radius * radius + (int) Math.min(1024L, Math.max(0L, queueDepth))));
        final long key = stableKey(playerId, operation, motionIntent == null ? "world" : motionIntent.worldKey(), radius);
        final AGCScale19LogicKernel.Grant kernelGrant = AGCScale19LogicKernel.INSTANCE.claim(
            AGCScale19LogicKernel.Axis.CHUNK_FORECAST,
            Math.max(1L, hints / 4L),
            "chunk-demand-forecast " + safe(reason)
        );
        this.lastRadius = radius;
        this.lastHints = hints;
        this.lastStableKey = key;
        if (!kernelGrant.admitted()) {
            this.fifoWait.incrementAndGet();
            this.lastReason = kernelGrant.reason();
            return new Forecast(false, key, radius, hints, true, this.lastReason);
        }
        this.forecasted.incrementAndGet();
        this.lastReason = "forecast stableKey=" + Long.toUnsignedString(key) + " radius=" + radius + " hints=" + hints;
        return new Forecast(true, key, radius, hints, false, this.lastReason);
    }

    public String statusLine() {
        return "AGCChunkDemandForecastTable{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", forecasted=" + this.forecasted.get()
            + ", fifoWait=" + this.fifoWait.get()
            + ", lastRadius=" + this.lastRadius
            + ", lastHints=" + this.lastHints
            + ", lastStableKey=" + Long.toUnsignedString(this.lastStableKey)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final UUID playerId, final AGCChunkBudget.Operation operation, final String worldKey, final int radius) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, playerId == null ? 0L : playerId.getMostSignificantBits());
        hash = mix(hash, playerId == null ? 0L : playerId.getLeastSignificantBits());
        hash = mix(hash, operation == null ? 0L : operation.ordinal());
        hash = mix(hash, worldKey == null ? 0L : worldKey.hashCode());
        hash = mix(hash, radius);
        return hash;
    }

    private static long mix(final long hash, final long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "chunk" : reason;
    }

    public record Forecast(boolean admitted, long stableKey, int radius, int hints, boolean fifoWait, String reason) {}
}
