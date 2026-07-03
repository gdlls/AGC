package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only witness index for entity visibility/interaction candidate planning.
 *
 * A witness is a planning hint for tracker work. This class never owns entity
 * state and never skips entity ticks, AI, damage, interaction or Bukkit events.
 */
public final class AGCEntityWitnessIndex {
    public static final AGCEntityWitnessIndex INSTANCE = new AGCEntityWitnessIndex();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong indexed = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastWitnesses;
    private volatile int lastBands;
    private volatile long lastWitnessKey;
    private volatile String lastReason = "cold";

    private AGCEntityWitnessIndex() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "tick reset";
    }

    public WitnessPlan index(final UUID playerId, final int estimatedEntities, final int spatialBands, final String reason) {
        this.requested.incrementAndGet();
        final int safeEntities = Math.max(0, estimatedEntities);
        final int bands = Math.max(1, Math.min(512, Math.max(1, spatialBands)));
        final int witnesses = Math.max(1, Math.min(262_144, safeEntities / Math.max(1, bands) + bands));
        final long key = stableKey(playerId, safeEntities, bands);
        final AGCScale20LosslessPipeline.Grant grant = AGCScale20LosslessPipeline.INSTANCE.claim(
            AGCScale20LosslessPipeline.Stage.ENTITY_INDEX,
            Math.max(1L, witnesses / 8L + bands),
            "entity-witness " + safe(reason)
        );
        this.lastWitnesses = witnesses;
        this.lastBands = bands;
        this.lastWitnessKey = key;
        if (!grant.admitted()) {
            this.delayed.incrementAndGet();
            this.lastReason = grant.reason();
            return new WitnessPlan(false, witnesses, bands, key, this.lastReason);
        }
        this.indexed.incrementAndGet();
        this.lastReason = "indexed read-only witnesses=" + witnesses + " bands=" + bands;
        return new WitnessPlan(true, witnesses, bands, key, this.lastReason);
    }

    public String statusLine() {
        return "AGCEntityWitnessIndex{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", indexed=" + this.indexed.get()
            + ", delayed=" + this.delayed.get()
            + ", lastWitnesses=" + this.lastWitnesses
            + ", lastBands=" + this.lastBands
            + ", lastWitnessKey=" + Long.toUnsignedString(this.lastWitnessKey)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final UUID playerId, final int estimatedEntities, final int bands) {
        long hash = 0xbb67ae8584caa73bL;
        hash = mix(hash, playerId == null ? 0L : playerId.getMostSignificantBits());
        hash = mix(hash, playerId == null ? 0L : playerId.getLeastSignificantBits());
        hash = mix(hash, estimatedEntities);
        hash = mix(hash, bands);
        return hash;
    }

    private static long mix(final long hash, final long value) {
        long v = hash ^ (value * 0xbf58476d1ce4e5b9L);
        return v ^ (v >>> 29);
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "entity" : reason;
    }

    public record WitnessPlan(boolean admitted, int witnesses, int bands, long witnessKey, String reason) {}
}
