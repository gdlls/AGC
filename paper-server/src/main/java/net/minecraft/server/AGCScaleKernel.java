package net.minecraft.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC alpha14 survival-scale kernel.
 * <p>
 * This is the large-server coordinator for the "thousands of ordinary survival
 * players at 20 TPS" target. It does not own live world state. Instead it
 * allocates per-tick capacity to invisible work only: read-only fanout
 * planning, chunk intent preparation, visibility pre-selection, compression
 * hints and plugin translation bookkeeping. Bukkit-visible mutation, entity
 * ticks, redstone, chunk state transitions and plugin callbacks still flow
 * through ordered commit lanes.
 */
public final class AGCScaleKernel {
    public static final AGCScaleKernel INSTANCE = new AGCScaleKernel();

    public enum Mode {
        NORMAL,
        DENSE_SURVIVAL,
        THOUSAND_PLAYER,
        EXTREME_HOTSPOT
    }

    public enum Axis {
        NETWORK_FANOUT,
        CHUNK_PIPELINE,
        ENTITY_TRACKER,
        WORLD_PREPARE,
        PLAYER_SESSION,
        PLUGIN_TRANSLATION,
        IO_COMPRESSION,
        HOTSPOT_ACCOUNTING
    }

    private final EnumMap<Axis, AtomicLong> remaining = new EnumMap<>(Axis.class);
    private final EnumMap<Axis, AtomicLong> admitted = new EnumMap<>(Axis.class);
    private final EnumMap<Axis, AtomicLong> delayed = new EnumMap<>(Axis.class);
    private volatile boolean enabled = true;
    private volatile int targetPlayers = 3000;
    private volatile double targetTps = 20.0D;
    private volatile int reserveCpuPercent = 22;
    private volatile long unitsPerCore = 220_000L;
    private volatile int maxWorkers = 96;
    private volatile long tickSequence;
    private volatile int lastPlayers;
    private volatile int lastWorlds;
    private volatile int lastLoadedChunks;
    private volatile int lastEntities;
    private volatile double lastMspt;
    private volatile int lastUsableWorkers = 1;
    private volatile Mode lastMode = Mode.NORMAL;
    private volatile long totalUnits;

    private AGCScaleKernel() {
        for (final Axis axis : Axis.values()) {
            this.remaining.put(axis, new AtomicLong());
            this.admitted.put(axis, new AtomicLong());
            this.delayed.put(axis, new AtomicLong());
        }
    }

    public void configure(final boolean enabled, final int targetPlayers, final double targetTps, final int reserveCpuPercent, final long unitsPerCore, final int maxWorkers) {
        this.enabled = enabled;
        this.targetPlayers = Math.max(1, targetPlayers);
        this.targetTps = Math.max(1.0D, targetTps);
        this.reserveCpuPercent = Math.max(0, Math.min(80, reserveCpuPercent));
        this.unitsPerCore = Math.max(1024L, unitsPerCore);
        this.maxWorkers = Math.max(1, maxWorkers);
    }

    public void beginTick(final long sequence, final int players, final int worlds, final int loadedChunks, final int entities, final double mspt) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastPlayers = Math.max(0, players);
        this.lastWorlds = Math.max(0, worlds);
        this.lastLoadedChunks = Math.max(0, loadedChunks);
        this.lastEntities = Math.max(0, entities);
        this.lastMspt = Math.max(0.0D, mspt);
        this.lastMode = classifyMode(this.lastPlayers, this.lastLoadedChunks, this.lastEntities, this.lastMspt);

        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedByPercent = Math.max(1, (processors * this.reserveCpuPercent + 99) / 100);
        final int reservedForVisible = Math.max(2, reservedByPercent);
        this.lastUsableWorkers = Math.max(1, Math.min(this.maxWorkers, processors - reservedForVisible));

        final double targetMspt = 1000.0D / this.targetTps;
        final double pressure = targetMspt <= 0.0D ? 1.0D : Math.max(0.25D, Math.min(2.0D, targetMspt / Math.max(1.0D, this.lastMspt)));
        final long base = Math.max(1L, Math.round(this.lastUsableWorkers * this.unitsPerCore * pressure));
        final long playerScale = Math.max(1L, Math.min(4L, (this.lastPlayers + 999L) / 1000L));
        this.totalUnits = this.enabled ? Math.max(4096L, base * playerScale) : 0L;

        for (final Axis axis : Axis.values()) {
            final long share = switch (axis) {
                case NETWORK_FANOUT -> this.totalUnits * 24L / 100L;
                case CHUNK_PIPELINE -> this.totalUnits * 23L / 100L;
                case ENTITY_TRACKER -> this.totalUnits * 18L / 100L;
                case WORLD_PREPARE -> this.totalUnits * 11L / 100L;
                case PLAYER_SESSION -> this.totalUnits * 8L / 100L;
                case PLUGIN_TRANSLATION -> this.totalUnits * 6L / 100L;
                case IO_COMPRESSION -> this.totalUnits * 7L / 100L;
                case HOTSPOT_ACCOUNTING -> this.totalUnits * 3L / 100L;
            };
            this.remaining.get(axis).set(Math.max(256L, share));
        }
    }

    public Admission claim(final Axis axis, final long units, final String reason) {
        if (!this.enabled) {
            this.delayed.get(axis).incrementAndGet();
            return new Admission(false, axis, 0L, "scale kernel disabled: " + nullToEmpty(reason));
        }
        final long cost = Math.max(1L, units);
        final AtomicLong bucket = this.remaining.get(axis);
        while (true) {
            final long current = bucket.get();
            if (current < cost) {
                this.delayed.get(axis).incrementAndGet();
                return new Admission(false, axis, current, "axis budget waits without visible reorder: " + nullToEmpty(reason));
            }
            if (bucket.compareAndSet(current, current - cost)) {
                this.admitted.get(axis).incrementAndGet();
                return new Admission(true, axis, current - cost, nullToEmpty(reason));
            }
        }
    }

    public boolean hasHeadroom(final Axis axis, final long units) {
        return this.enabled && this.remaining.get(axis).get() >= Math.max(1L, units);
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.tickSequence,
            this.lastMode,
            this.targetPlayers,
            this.targetTps,
            this.lastPlayers,
            this.lastWorlds,
            this.lastLoadedChunks,
            this.lastEntities,
            this.lastMspt,
            this.lastUsableWorkers,
            this.totalUnits,
            copy(this.remaining),
            copy(this.admitted),
            copy(this.delayed)
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCScaleKernel{enabled=" + snapshot.enabled()
            + ", mode=" + snapshot.mode()
            + ", target=" + snapshot.targetPlayers() + "@" + snapshot.targetTps() + "tps"
            + ", livePlayers=" + snapshot.players()
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

    private Mode classifyMode(final int players, final int loadedChunks, final int entities, final double mspt) {
        if (players >= Math.max(2000, this.targetPlayers * 2 / 3) || loadedChunks >= 180_000 || entities >= 300_000) {
            return mspt >= 45.0D ? Mode.EXTREME_HOTSPOT : Mode.THOUSAND_PLAYER;
        }
        if (players >= 512 || loadedChunks >= 45_000 || entities >= 80_000) {
            return Mode.DENSE_SURVIVAL;
        }
        return Mode.NORMAL;
    }

    private static Map<Axis, Long> copy(final EnumMap<Axis, AtomicLong> source) {
        final EnumMap<Axis, Long> copy = new EnumMap<>(Axis.class);
        for (final Map.Entry<Axis, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    public record Admission(boolean admitted, Axis axis, long remainingUnits, String reason) {
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
        Map<Axis, Long> remaining,
        Map<Axis, Long> admitted,
        Map<Axis, Long> delayed
    ) {
    }
}
