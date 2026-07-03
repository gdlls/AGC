package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Terrain-aware demand model for FIFO-compatible chunk planning. */
public final class AGCChunkTerrainDemandModel {
    public static final AGCChunkTerrainDemandModel INSTANCE = new AGCChunkTerrainDemandModel();

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();
    private volatile long tickSequence;
    private volatile int terrainBudget = 65_536;
    private volatile String lastReason = "cold";

    private AGCChunkTerrainDemandModel() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.terrainBudget = 65_536 + (int) Math.min(32_768L, sequence % 32_768L);
        this.lastReason = "reset terrainBudget=" + this.terrainBudget;
    }

    public Forecast forecast(final UUID playerId, final AGCChunkBudget.Operation operation, final int lookaheadRadius, final int cohortSize, final String reason) {
        this.planned.incrementAndGet();
        final int radius = Math.max(1, lookaheadRadius);
        final int cohort = Math.max(1, cohortSize);
        final int opWeight = operation == AGCChunkBudget.Operation.GENERATE ? 5 : operation == AGCChunkBudget.Operation.LOAD ? 3 : 1;
        final int demand = radius * radius * opWeight + cohort * 12;
        final boolean admitted = demand <= this.terrainBudget;
        if (!admitted) {
            this.delayed.incrementAndGet();
        }
        final int stripes = Math.max(1, Math.min(128, this.terrainBudget / Math.max(1, radius * opWeight * 32)));
        this.lastReason = (admitted ? "forecast" : "fifo-delay") + " terrain demand op=" + (operation == null ? "unknown" : operation.name()) + " demand=" + demand + " stripes=" + stripes + " reason=" + safe(reason);
        return new Forecast(admitted, demand, stripes, mix(playerId == null ? 0L : playerId.getMostSignificantBits(), radius, cohort, opWeight), this.lastReason);
    }

    public String statusLine() {
        return "AGCChunkTerrainDemandModel{tick=" + this.tickSequence
            + ", terrainBudget=" + this.terrainBudget
            + ", planned=" + this.planned.get()
            + ", delayed=" + this.delayed.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String reason) { return reason == null || reason.isBlank() ? "unspecified" : reason; }

    private static long mix(final long a, final long b, final long c, final long d) {
        long x = a ^ 0x9E3779B97F4A7C15L;
        x ^= b + 0xBF58476D1CE4E5B9L + (x << 6) + (x >>> 2);
        x ^= c + 0x94D049BB133111EBL + (x << 6) + (x >>> 2);
        x ^= d + 0xD6E8FEB86659FD93L + (x << 6) + (x >>> 2);
        return x;
    }

    public record Forecast(boolean admitted, int demand, int stripes, long stableKey, String reason) {}
}
