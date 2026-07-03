package net.minecraft.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha16 hard optimisation control law.
 * <p>
 * This is an algorithmic controller rather than a fallback switch: it estimates
 * tick pressure with an EWMA + error derivative, then gives more budget to
 * invisible work that lowers future main-thread cost while preserving Minecraft
 * logic phases. Bukkit-visible mutation, plugin callbacks, chunk completion and
 * entity ticks are still outside this controller.
 */
public final class AGCScale16ControlLaw {
    public static final AGCScale16ControlLaw INSTANCE = new AGCScale16ControlLaw();

    public enum Axis {
        NETWORK_DELTA,
        PLAYER_MOTION,
        CHUNK_LOOKAHEAD,
        ENTITY_SPATIAL_INDEX,
        WORLD_LOGIC_DAG,
        PLUGIN_SEMANTICS,
        MEMORY_SCRATCH
    }

    private final EnumMap<Axis, AtomicLong> remaining = new EnumMap<>(Axis.class);
    private final EnumMap<Axis, AtomicLong> admitted = new EnumMap<>(Axis.class);
    private final EnumMap<Axis, AtomicLong> delayed = new EnumMap<>(Axis.class);
    private volatile boolean enabled = true;
    private volatile double targetMspt = 50.0D;
    private volatile double ewmaMspt = 50.0D;
    private volatile double previousError;
    private volatile double pressure = 1.0D;
    private volatile long tickSequence;
    private volatile int players;
    private volatile int worlds;
    private volatile int loadedChunks;
    private volatile int entities;
    private volatile long baseUnits = 640_000L;

    private AGCScale16ControlLaw() {
        for (final Axis axis : Axis.values()) {
            this.remaining.put(axis, new AtomicLong());
            this.admitted.put(axis, new AtomicLong());
            this.delayed.put(axis, new AtomicLong());
        }
    }

    public void configure(final boolean enabled, final double targetTps, final long baseUnits) {
        this.enabled = enabled;
        final double safeTps = Math.max(1.0D, targetTps);
        this.targetMspt = 1000.0D / safeTps;
        this.baseUnits = Math.max(32_768L, baseUnits);
    }

    public void beginTick(final long sequence, final int players, final int worlds, final int loadedChunks, final int entities, final double mspt) {
        this.tickSequence = Math.max(0L, sequence);
        this.players = Math.max(0, players);
        this.worlds = Math.max(0, worlds);
        this.loadedChunks = Math.max(0, loadedChunks);
        this.entities = Math.max(0, entities);
        final double sample = Math.max(0.0D, mspt);
        this.ewmaMspt = this.ewmaMspt * 0.875D + sample * 0.125D;
        final double error = this.targetMspt - this.ewmaMspt;
        final double derivative = error - this.previousError;
        this.previousError = error;
        final double proportional = error / Math.max(1.0D, this.targetMspt);
        final double derivativeTerm = derivative / Math.max(1.0D, this.targetMspt);
        this.pressure = clamp(0.18D, 2.40D, 1.0D + proportional * 0.85D + derivativeTerm * 0.35D);

        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final long playerScale = Math.max(1L, Math.min(6L, (long) (this.players + 511) / 512L));
        final long topologyUnits = Math.round(this.baseUnits * Math.max(1, processors - 2) * this.pressure * playerScale);
        final long total = this.enabled ? Math.max(8192L, topologyUnits) : 0L;
        for (final Axis axis : Axis.values()) {
            final long share = switch (axis) {
                case NETWORK_DELTA -> total * 20L / 100L;
                case PLAYER_MOTION -> total * 10L / 100L;
                case CHUNK_LOOKAHEAD -> total * 22L / 100L;
                case ENTITY_SPATIAL_INDEX -> total * 20L / 100L;
                case WORLD_LOGIC_DAG -> total * 10L / 100L;
                case PLUGIN_SEMANTICS -> total * 8L / 100L;
                case MEMORY_SCRATCH -> total * 10L / 100L;
            };
            this.remaining.get(axis).set(Math.max(512L, share));
        }
    }

    public Admission claim(final Axis axis, final long units, final String reason) {
        if (!this.enabled) {
            this.delayed.get(axis).incrementAndGet();
            return new Admission(false, axis, 0L, "scale16 disabled: " + safe(reason));
        }
        final long cost = Math.max(1L, units);
        final AtomicLong bucket = this.remaining.get(axis);
        while (true) {
            final long current = bucket.get();
            if (current < cost) {
                this.delayed.get(axis).incrementAndGet();
                return new Admission(false, axis, current, "scale16 budget waits; ordered semantics preserved: " + safe(reason));
            }
            if (bucket.compareAndSet(current, current - cost)) {
                this.admitted.get(axis).incrementAndGet();
                return new Admission(true, axis, current - cost, safe(reason));
            }
        }
    }

    public double pressure() {
        return this.pressure;
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.tickSequence, this.targetMspt, this.ewmaMspt, this.pressure, this.players, this.worlds, this.loadedChunks, this.entities, copy(this.remaining), copy(this.admitted), copy(this.delayed));
    }

    public String statusLine() {
        final Snapshot s = this.snapshot();
        return "AGCScale16ControlLaw{enabled=" + s.enabled()
            + ", tick=" + s.tickSequence()
            + ", targetMspt=" + s.targetMspt()
            + ", ewmaMspt=" + s.ewmaMspt()
            + ", pressure=" + s.pressure()
            + ", players=" + s.players()
            + ", worlds=" + s.worlds()
            + ", chunks=" + s.loadedChunks()
            + ", entities=" + s.entities()
            + ", remaining=" + s.remaining()
            + ", delayed=" + s.delayed()
            + '}';
    }

    private static double clamp(final double min, final double max, final double value) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safe(final String value) {
        return value == null ? "" : value;
    }

    private static Map<Axis, Long> copy(final EnumMap<Axis, AtomicLong> source) {
        final EnumMap<Axis, Long> copy = new EnumMap<>(Axis.class);
        for (final Map.Entry<Axis, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(copy);
    }

    public record Admission(boolean admitted, Axis axis, long remainingUnits, String reason) {
    }

    public record Snapshot(boolean enabled, long tickSequence, double targetMspt, double ewmaMspt, double pressure, int players, int worlds, int loadedChunks, int entities, Map<Axis, Long> remaining, Map<Axis, Long> admitted, Map<Axis, Long> delayed) {
    }
}
