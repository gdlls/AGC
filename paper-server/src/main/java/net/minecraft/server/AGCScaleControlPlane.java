package net.minecraft.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha15 control plane for the heavy survival-scale optimisations.
 * <p>
 * The control plane is deliberately limited to invisible work: packet shape
 * planning, player cohort routing, chunk intent compaction, entity visibility
 * banding, plugin translation bookkeeping and memory-local scratch planning. It
 * does not own world state and it never grants permission to skip gameplay
 * ticks, mutate Bukkit objects off-thread, drop packets or reorder chunk FIFO.
 */
public final class AGCScaleControlPlane {
    public static final AGCScaleControlPlane INSTANCE = new AGCScaleControlPlane();

    public enum Mode {
        STEADY,
        DENSE_SURVIVAL,
        THOUSAND_PLAYER,
        SATURATED_HOTSPOT
    }

    public enum Lane {
        PACKET_SHAPE,
        PLAYER_COHORT,
        CHUNK_INTENT_COMPACTION,
        ENTITY_VISIBILITY_BANDING,
        PLUGIN_TRANSLATION,
        MEMORY_LOCALITY,
        ENCODING_HELPER,
        HOTSPOT_EVICTION
    }

    private final EnumMap<Lane, AtomicLong> remaining = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> admitted = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> delayed = new EnumMap<>(Lane.class);
    private volatile boolean enabled = true;
    private volatile int targetPlayers = 3000;
    private volatile double targetTps = 20.0D;
    private volatile int reserveCpuPercent = 24;
    private volatile long unitsPerCore = 260_000L;
    private volatile long tickSequence;
    private volatile Mode mode = Mode.STEADY;
    private volatile int players;
    private volatile int worlds;
    private volatile int loadedChunks;
    private volatile int entities;
    private volatile double mspt;
    private volatile int usableWorkers = 1;
    private volatile long totalUnits;

    private AGCScaleControlPlane() {
        for (final Lane lane : Lane.values()) {
            this.remaining.put(lane, new AtomicLong());
            this.admitted.put(lane, new AtomicLong());
            this.delayed.put(lane, new AtomicLong());
        }
    }

    public void configure(final boolean enabled, final int targetPlayers, final double targetTps, final int reserveCpuPercent, final long unitsPerCore) {
        this.enabled = enabled;
        this.targetPlayers = Math.max(1, targetPlayers);
        this.targetTps = Math.max(1.0D, targetTps);
        this.reserveCpuPercent = Math.max(0, Math.min(85, reserveCpuPercent));
        this.unitsPerCore = Math.max(4096L, unitsPerCore);
    }

    public void beginTick(final long sequence, final int players, final int worlds, final int loadedChunks, final int entities, final double mspt) {
        this.tickSequence = Math.max(0L, sequence);
        this.players = Math.max(0, players);
        this.worlds = Math.max(0, worlds);
        this.loadedChunks = Math.max(0, loadedChunks);
        this.entities = Math.max(0, entities);
        this.mspt = Math.max(0.0D, mspt);
        this.mode = classify(this.players, this.loadedChunks, this.entities, this.mspt);

        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reserved = Math.max(2, (processors * this.reserveCpuPercent + 99) / 100);
        this.usableWorkers = Math.max(1, processors - reserved);
        final double targetMspt = 1000.0D / this.targetTps;
        final double pressureMultiplier = targetMspt <= 0.0D ? 1.0D : Math.max(0.20D, Math.min(2.25D, targetMspt / Math.max(1.0D, this.mspt)));
        final long playerMultiplier = Math.max(1L, Math.min(5L, (this.players + 799L) / 800L));
        this.totalUnits = this.enabled ? Math.max(8192L, Math.round(this.usableWorkers * this.unitsPerCore * pressureMultiplier) * playerMultiplier) : 0L;

        for (final Lane lane : Lane.values()) {
            final long share = switch (lane) {
                case PACKET_SHAPE -> this.totalUnits * 22L / 100L;
                case PLAYER_COHORT -> this.totalUnits * 10L / 100L;
                case CHUNK_INTENT_COMPACTION -> this.totalUnits * 20L / 100L;
                case ENTITY_VISIBILITY_BANDING -> this.totalUnits * 18L / 100L;
                case PLUGIN_TRANSLATION -> this.totalUnits * 8L / 100L;
                case MEMORY_LOCALITY -> this.totalUnits * 7L / 100L;
                case ENCODING_HELPER -> this.totalUnits * 10L / 100L;
                case HOTSPOT_EVICTION -> this.totalUnits * 5L / 100L;
            };
            this.remaining.get(lane).set(Math.max(512L, share));
        }
    }

    public Admission claim(final Lane lane, final long units, final String reason) {
        if (!this.enabled) {
            this.delayed.get(lane).incrementAndGet();
            return new Admission(false, lane, 0L, "scale control disabled: " + sanitize(reason));
        }
        final long cost = Math.max(1L, units);
        final AtomicLong bucket = this.remaining.get(lane);
        while (true) {
            final long current = bucket.get();
            if (current < cost) {
                this.delayed.get(lane).incrementAndGet();
                return new Admission(false, lane, current, "lane budget waits without semantic change: " + sanitize(reason));
            }
            if (bucket.compareAndSet(current, current - cost)) {
                this.admitted.get(lane).incrementAndGet();
                return new Admission(true, lane, current - cost, sanitize(reason));
            }
        }
    }

    public boolean hasHeadroom(final Lane lane, final long units) {
        return this.enabled && this.remaining.get(lane).get() >= Math.max(1L, units);
    }

    public Mode mode() {
        return this.mode;
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.tickSequence, this.mode, this.targetPlayers, this.targetTps, this.players, this.worlds, this.loadedChunks, this.entities, this.mspt, this.usableWorkers, this.totalUnits, copy(this.remaining), copy(this.admitted), copy(this.delayed));
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCScaleControlPlane{enabled=" + snapshot.enabled()
            + ", mode=" + snapshot.mode()
            + ", target=" + snapshot.targetPlayers() + "@" + snapshot.targetTps() + "tps"
            + ", players=" + snapshot.players()
            + ", worlds=" + snapshot.worlds()
            + ", chunks=" + snapshot.loadedChunks()
            + ", entities=" + snapshot.entities()
            + ", mspt=" + snapshot.mspt()
            + ", workers=" + snapshot.usableWorkers()
            + ", totalUnits=" + snapshot.totalUnits()
            + ", remaining=" + snapshot.remaining()
            + ", delayed=" + snapshot.delayed()
            + '}';
    }

    private Mode classify(final int players, final int loadedChunks, final int entities, final double mspt) {
        if (players >= Math.max(2400, this.targetPlayers * 4 / 5) || loadedChunks >= 220_000 || entities >= 360_000 || mspt >= 47.5D) {
            return Mode.SATURATED_HOTSPOT;
        }
        if (players >= Math.max(1400, this.targetPlayers / 2) || loadedChunks >= 120_000 || entities >= 200_000) {
            return Mode.THOUSAND_PLAYER;
        }
        if (players >= 512 || loadedChunks >= 45_000 || entities >= 75_000) {
            return Mode.DENSE_SURVIVAL;
        }
        return Mode.STEADY;
    }

    private static Map<Lane, Long> copy(final EnumMap<Lane, AtomicLong> source) {
        final EnumMap<Lane, Long> copy = new EnumMap<>(Lane.class);
        for (final Map.Entry<Lane, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String sanitize(final String value) {
        return value == null ? "" : value;
    }

    public record Admission(boolean admitted, Lane lane, long remainingUnits, String reason) {
    }

    public record Snapshot(
        boolean enabled,
        long tickSequence,
        Mode mode,
        int targetPlayers,
        double targetTps,
        int players,
        int worlds,
        int loadedChunks,
        int entities,
        double mspt,
        int usableWorkers,
        long totalUnits,
        Map<Lane, Long> remaining,
        Map<Lane, Long> admitted,
        Map<Lane, Long> delayed
    ) {
    }
}
