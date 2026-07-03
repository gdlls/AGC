package net.minecraft.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stable read-only entity candidate index. It groups candidates by visibility
 * band so tracker scans avoid repeated distance work. It never skips entity
 * ticks, AI, damage, interaction or Bukkit events.
 */
public final class AGCEntitySpatialBandIndex {
    public static final AGCEntitySpatialBandIndex INSTANCE = new AGCEntitySpatialBandIndex();

    public enum Band {
        INTERACTION,
        NEAR,
        MID,
        FAR,
        OUTSIDE_TRACKING
    }

    private final EnumMap<Band, AtomicLong> preparedByBand = new EnumMap<>(Band.class);
    private final AtomicLong waits = new AtomicLong();
    private volatile long tickSequence;
    private volatile int hardCandidateCap = 8192;

    private AGCEntitySpatialBandIndex() {
        for (final Band band : Band.values()) {
            this.preparedByBand.put(band, new AtomicLong());
        }
    }

    public void configure(final int hardCandidateCap) {
        this.hardCandidateCap = Math.max(128, Math.min(131_072, hardCandidateCap));
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Admission prepare(final UUID playerId, final int estimatedEntities, final String reason) {
        final int candidates = Math.max(0, Math.min(this.hardCandidateCap, estimatedEntities));
        final Band band = classify(candidates);
        final long cost = Math.max(1L, candidates / 16L + 1L);
        final AGCScale16ControlLaw.Admission budget = AGCScale16ControlLaw.INSTANCE.claim(AGCScale16ControlLaw.Axis.ENTITY_SPATIAL_INDEX, cost, reason);
        if (!budget.admitted()) {
            this.waits.incrementAndGet();
            return new Admission(false, band, candidates, budget.reason());
        }
        this.preparedByBand.get(band).incrementAndGet();
        return new Admission(true, band, candidates, "stable read-only entity band for " + (playerId == null ? "unknown" : playerId));
    }

    public String statusLine() {
        return "AGCEntitySpatialBandIndex{tick=" + this.tickSequence
            + ", hardCandidateCap=" + this.hardCandidateCap
            + ", prepared=" + snapshot()
            + ", waits=" + this.waits.get()
            + '}';
    }

    public Map<Band, Long> snapshot() {
        final EnumMap<Band, Long> copy = new EnumMap<>(Band.class);
        for (final Map.Entry<Band, AtomicLong> entry : this.preparedByBand.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Band classify(final int candidates) {
        if (candidates <= 16) {
            return Band.INTERACTION;
        }
        if (candidates <= 96) {
            return Band.NEAR;
        }
        if (candidates <= 512) {
            return Band.MID;
        }
        if (candidates <= 4096) {
            return Band.FAR;
        }
        return Band.OUTSIDE_TRACKING;
    }

    public record Admission(boolean admitted, Band band, int candidates, String reason) {
    }
}
