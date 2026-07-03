package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC alpha13 compute topology planner.
 * <p>
 * This class does not own Minecraft state and does not execute Bukkit-visible
 * work. It converts the current hardware/server pressure into budgets for
 * semantic-safe lanes: read-only prepare, fanout planning, compression helpers,
 * chunk intent planning and ordered commit assist. The primary thread, plugin
 * callbacks, Netty and GC are always reserved first so using more cores does not
 * steal time from visible gameplay.
 */
public final class AGCComputeTopologyPlanner {
    public static final AGCComputeTopologyPlanner INSTANCE = new AGCComputeTopologyPlanner();

    public enum Lane {
        READ_ONLY_PREPARE,
        FANOUT_DEDUP,
        CHUNK_INTENT,
        ENTITY_VISIBILITY,
        STORAGE_IO,
        COMPRESSION,
        ORDERED_COMMIT_ASSIST
    }

    public enum Pressure {
        ROOMY,
        BUSY,
        SATURATED,
        OVERLOADED
    }

    private final EnumMap<Lane, AtomicLong> tokens = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> requests = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> grants = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> waits = new EnumMap<>(Lane.class);

    private volatile boolean enabled = true;
    private volatile int reserveCores = 2;
    private volatile int reserveCpuPercent = 22;
    private volatile long unitsPerWorker = 96_000L;
    private volatile long tickSequence;
    private volatile int processors = 1;
    private volatile int workers = 1;
    private volatile int ioWorkers = 1;
    private volatile int compressionWorkers = 1;
    private volatile int players;
    private volatile double mspt;
    private volatile Pressure pressure = Pressure.ROOMY;
    private volatile long lastTotalUnits;

    private AGCComputeTopologyPlanner() {
        for (final Lane lane : Lane.values()) {
            this.tokens.put(lane, new AtomicLong());
            this.requests.put(lane, new AtomicLong());
            this.grants.put(lane, new AtomicLong());
            this.waits.put(lane, new AtomicLong());
        }
    }

    public void configure(final boolean enabled, final int reserveCores, final int reserveCpuPercent, final long unitsPerWorker) {
        this.enabled = enabled;
        this.reserveCores = Math.max(1, reserveCores);
        this.reserveCpuPercent = Math.max(0, Math.min(80, reserveCpuPercent));
        this.unitsPerWorker = Math.max(8192L, unitsPerWorker);
    }

    public void beginTick(final long sequence, final int onlinePlayers, final double mspt) {
        this.tickSequence = Math.max(0L, sequence);
        this.players = Math.max(0, onlinePlayers);
        this.mspt = Math.max(0.0D, mspt);
        this.processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.pressure = classify(this.players, this.mspt);
        final int cpuReserved = Math.max(this.reserveCores, (this.processors * this.reserveCpuPercent + 99) / 100);
        final int available = Math.max(1, this.processors - cpuReserved);
        final int pressureDivisor = switch (this.pressure) {
            case ROOMY -> 1;
            case BUSY -> 2;
            case SATURATED -> 3;
            case OVERLOADED -> 5;
        };
        this.workers = Math.max(1, available / pressureDivisor);
        this.ioWorkers = Math.max(1, Math.min(4, available / 3 + 1));
        this.compressionWorkers = Math.max(1, Math.min(4, available / 4 + 1));
        this.lastTotalUnits = Math.max(4096L, this.workers * this.unitsPerWorker);
        refill(this.lastTotalUnits);
    }

    public Admission claim(final Lane lane, final long cost, final String reason) {
        final Lane safeLane = lane == null ? Lane.READ_ONLY_PREPARE : lane;
        final long safeCost = Math.max(1L, cost);
        this.requests.get(safeLane).incrementAndGet();
        if (!this.enabled) {
            this.grants.get(safeLane).incrementAndGet();
            return new Admission(true, Long.MAX_VALUE, "topology disabled: " + nullToEmpty(reason));
        }
        final AtomicLong bucket = this.tokens.get(safeLane);
        while (true) {
            final long current = bucket.get();
            if (current < safeCost) {
                this.waits.get(safeLane).incrementAndGet();
                AGCSemanticInvariant.INSTANCE.record(
                    AGCSemanticInvariant.Domain.PLAYER,
                    AGCSemanticInvariant.Decision.CONFLICT_SERIALISED,
                    "compute topology wait lane=" + safeLane + " reason=" + nullToEmpty(reason)
                );
                return new Admission(false, current, "compute topology wait: " + safeLane + " needs " + safeCost + " has " + current);
            }
            final long next = current - safeCost;
            if (bucket.compareAndSet(current, next)) {
                this.grants.get(safeLane).incrementAndGet();
                return new Admission(true, next, "compute topology grant: " + safeLane + ' ' + nullToEmpty(reason));
            }
        }
    }

    public int readOnlyWorkers() {
        return this.workers;
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.tickSequence,
            this.processors,
            this.workers,
            this.ioWorkers,
            this.compressionWorkers,
            this.players,
            this.mspt,
            this.pressure,
            this.lastTotalUnits,
            copy(this.tokens),
            copy(this.requests),
            copy(this.grants),
            copy(this.waits)
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCComputeTopologyPlanner{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", processors=" + snapshot.processors()
            + ", readOnlyWorkers=" + snapshot.readOnlyWorkers()
            + ", ioWorkers=" + snapshot.ioWorkers()
            + ", compressionWorkers=" + snapshot.compressionWorkers()
            + ", players=" + snapshot.players()
            + ", mspt=" + snapshot.mspt()
            + ", pressure=" + snapshot.pressure()
            + ", units=" + snapshot.totalUnits()
            + ", remaining=" + snapshot.remaining()
            + ", waits=" + snapshot.waits()
            + '}';
    }

    private void refill(final long totalUnits) {
        final long divisor = Math.max(1, Lane.values().length);
        for (final Map.Entry<Lane, AtomicLong> entry : this.tokens.entrySet()) {
            final long weight = switch (entry.getKey()) {
                case READ_ONLY_PREPARE -> 4L;
                case FANOUT_DEDUP -> 5L;
                case CHUNK_INTENT -> 4L;
                case ENTITY_VISIBILITY -> 4L;
                case STORAGE_IO -> 2L;
                case COMPRESSION -> 2L;
                case ORDERED_COMMIT_ASSIST -> 1L;
            };
            final long refill = Math.max(1L, (totalUnits * weight) / (divisor * 3L));
            final long maxCarry = Math.max(refill, refill * 3L);
            final AtomicLong bucket = entry.getValue();
            while (true) {
                final long current = bucket.get();
                final long next = Math.min(maxCarry, current + refill);
                if (bucket.compareAndSet(current, next)) {
                    break;
                }
            }
        }
    }

    private Pressure classify(final int players, final double mspt) {
        if (mspt >= 50.0D) {
            return Pressure.OVERLOADED;
        }
        if (players >= 3000 || mspt >= 42.0D) {
            return Pressure.SATURATED;
        }
        if (players >= 1000 || mspt >= 30.0D) {
            return Pressure.BUSY;
        }
        return Pressure.ROOMY;
    }

    private static EnumMap<Lane, Long> copy(final EnumMap<Lane, AtomicLong> source) {
        final EnumMap<Lane, Long> copy = new EnumMap<>(Lane.class);
        for (final Map.Entry<Lane, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    public record Admission(boolean admitted, long remaining, String reason) {
    }

    public record Snapshot(
        boolean enabled,
        long tickSequence,
        int processors,
        int readOnlyWorkers,
        int ioWorkers,
        int compressionWorkers,
        int players,
        double mspt,
        Pressure pressure,
        long totalUnits,
        EnumMap<Lane, Long> remaining,
        EnumMap<Lane, Long> requests,
        EnumMap<Lane, Long> grants,
        EnumMap<Lane, Long> waits
    ) {
    }
}
