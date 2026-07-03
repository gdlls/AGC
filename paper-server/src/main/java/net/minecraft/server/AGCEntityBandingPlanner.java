package net.minecraft.server;

import java.util.EnumMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only entity visibility banding. This plans candidate buckets for tracker
 * calculations but never skips entity ticks, AI, damage, interactions or Bukkit
 * events.
 */
public final class AGCEntityBandingPlanner {
    public static final AGCEntityBandingPlanner INSTANCE = new AGCEntityBandingPlanner();

    public enum Band {
        SELF_AND_INTERACTION,
        NEAR,
        MID,
        FAR,
        OUTER
    }

    private final EnumMap<Band, AtomicLong> preparedByBand = new EnumMap<>(Band.class);
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong prepared = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int maxCandidatesPerPlayer = 8192;
    private volatile long tickSequence;

    private AGCEntityBandingPlanner() {
        for (final Band band : Band.values()) {
            this.preparedByBand.put(band, new AtomicLong());
        }
    }

    public void configure(final boolean enabled, final int maxCandidatesPerPlayer) {
        this.enabled = enabled;
        this.maxCandidatesPerPlayer = Math.max(1, maxCandidatesPerPlayer);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Decision prepare(final UUID playerId, final int estimatedEntities, final String reason) {
        this.requests.incrementAndGet();
        if (!this.enabled || playerId == null || estimatedEntities <= 0) {
            this.ordered.incrementAndGet();
            return new Decision(false, Band.SELF_AND_INTERACTION, 0, "entity banding disabled or empty; tracker order unchanged");
        }
        final int candidates = Math.min(this.maxCandidatesPerPlayer, Math.max(1, estimatedEntities));
        final Band band = chooseBand(candidates);
        final long cost = Math.max(1L, candidates / 32L);
        final AGCScaleControlPlane.Admission control = AGCScaleControlPlane.INSTANCE.claim(AGCScaleControlPlane.Lane.ENTITY_VISIBILITY_BANDING, cost, reason);
        if (!control.admitted()) {
            this.ordered.incrementAndGet();
            return new Decision(false, band, candidates, control.reason());
        }
        this.prepared.incrementAndGet();
        this.preparedByBand.get(band).incrementAndGet();
        return new Decision(true, band, candidates, "read-only entity candidate band prepared; no entity tick or event skipped");
    }

    public String statusLine() {
        return "AGCEntityBandingPlanner{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", requests=" + this.requests.get()
            + ", prepared=" + this.prepared.get()
            + ", ordered=" + this.ordered.get()
            + ", bands=" + snapshotBands()
            + '}';
    }

    private EnumMap<Band, Long> snapshotBands() {
        final EnumMap<Band, Long> copy = new EnumMap<>(Band.class);
        for (final Band band : Band.values()) {
            copy.put(band, this.preparedByBand.get(band).get());
        }
        return copy;
    }

    private static Band chooseBand(final int candidates) {
        if (candidates <= 64) {
            return Band.SELF_AND_INTERACTION;
        }
        if (candidates <= 512) {
            return Band.NEAR;
        }
        if (candidates <= 2048) {
            return Band.MID;
        }
        if (candidates <= 8192) {
            return Band.FAR;
        }
        return Band.OUTER;
    }

    public record Decision(boolean prepared, Band band, int candidates, String reason) {
    }
}
