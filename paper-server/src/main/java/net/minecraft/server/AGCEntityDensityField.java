package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only entity density field used to reduce duplicate tracker candidate work. */
public final class AGCEntityDensityField {
    public static final AGCEntityDensityField INSTANCE = new AGCEntityDensityField();

    public enum DensityBand {
        SPARSE,
        NORMAL,
        DENSE,
        EXTREME
    }

    private final AtomicLong prepared = new AtomicLong();
    private final AtomicLong dense = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCEntityDensityField() {
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "reset";
    }

    public Field prepare(final UUID playerId, final int estimatedEntities, final int nearbyPlayers, final String reason) {
        this.prepared.incrementAndGet();
        final int entities = Math.max(0, estimatedEntities);
        final int players = Math.max(1, nearbyPlayers);
        final int densityScore = entities / Math.max(1, players);
        final DensityBand band = densityScore >= 512 ? DensityBand.EXTREME : densityScore >= 192 ? DensityBand.DENSE : densityScore >= 64 ? DensityBand.NORMAL : DensityBand.SPARSE;
        if (band == DensityBand.DENSE || band == DensityBand.EXTREME) {
            this.dense.incrementAndGet();
        }
        final int candidateBudget = Math.max(512, Math.min(262_144, entities + players * 16));
        this.lastReason = "band=" + band + " densityScore=" + densityScore + " candidateBudget=" + candidateBudget + " reason=" + safe(reason);
        return new Field(true, band, densityScore, candidateBudget, this.lastReason);
    }

    public String statusLine() {
        return "AGCEntityDensityField{tick=" + this.tickSequence
            + ", prepared=" + this.prepared.get()
            + ", dense=" + this.dense.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    public record Field(boolean admitted, DensityBand band, int densityScore, int candidateBudget, String reason) {}
}
