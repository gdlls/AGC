package net.minecraft.server;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC alpha12 resource efficiency engine.
 * <p>
 * This does not make gameplay decisions. It only budgets invisible work that is
 * already semantic-safe: read-only preparation, packet deadline batching,
 * duplicate fanout planning and ordered helper commits. Bukkit-visible world
 * changes still run through the normal ordered commit lanes.
 */
public final class AGCResourceEfficiencyEngine {
    public static final AGCResourceEfficiencyEngine INSTANCE = new AGCResourceEfficiencyEngine();

    public enum Domain {
        NETWORK,
        CHUNK,
        ENTITY,
        WORLD,
        PLAYER,
        MINIGAME,
        PLUGIN,
        IO
    }

    public enum Pressure {
        BASELINE,
        DENSE,
        EXTREME,
        CRITICAL
    }

    private final EnumMap<Domain, AtomicLong> tokens = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> requested = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> granted = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> delayed = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, Integer> weights = new EnumMap<>(Domain.class);

    private volatile boolean enabled = true;
    private volatile int targetPlayers = 3000;
    private volatile double targetTps = 20.0D;
    private volatile int reserveCpuPercent = 18;
    private volatile long maxMemoryPressureBytes = 1024L * 1024L * 1024L;
    private volatile long baseUnitsPerCore = 180_000L;
    private volatile long maxCarryMultiplier = 2L;
    private volatile long tickSequence;
    private volatile int lastPlayers;
    private volatile int lastWorlds;
    private volatile int lastChunks;
    private volatile int lastEntities;
    private volatile double lastMspt;
    private volatile Pressure pressure = Pressure.BASELINE;
    private volatile long computedUnits;
    private volatile long lastHeapFree;
    private volatile long lastAvailableProcessors;
    private volatile long lastProcessCpuLoadPermille;

    private AGCResourceEfficiencyEngine() {
        for (final Domain domain : Domain.values()) {
            this.tokens.put(domain, new AtomicLong());
            this.requested.put(domain, new AtomicLong());
            this.granted.put(domain, new AtomicLong());
            this.delayed.put(domain, new AtomicLong());
            this.weights.put(domain, defaultWeight(domain));
        }
    }

    public void configure(
        final boolean enabled,
        final int targetPlayers,
        final double targetTps,
        final int reserveCpuPercent,
        final long maxMemoryPressureBytes,
        final long baseUnitsPerCore,
        final long maxCarryMultiplier
    ) {
        this.enabled = enabled;
        this.targetPlayers = Math.max(1, targetPlayers);
        this.targetTps = Math.max(1.0D, targetTps);
        this.reserveCpuPercent = Math.max(0, Math.min(90, reserveCpuPercent));
        this.maxMemoryPressureBytes = Math.max(16L * 1024L * 1024L, maxMemoryPressureBytes);
        this.baseUnitsPerCore = Math.max(16_384L, baseUnitsPerCore);
        this.maxCarryMultiplier = Math.max(1L, Math.min(16L, maxCarryMultiplier));
    }

    public void beginTick(
        final long sequence,
        final int onlinePlayers,
        final int activeWorlds,
        final int loadedChunks,
        final int trackedEntities,
        final double mspt
    ) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastPlayers = Math.max(0, onlinePlayers);
        this.lastWorlds = Math.max(0, activeWorlds);
        this.lastChunks = Math.max(0, loadedChunks);
        this.lastEntities = Math.max(0, trackedEntities);
        this.lastMspt = Math.max(0.0D, mspt);
        this.lastAvailableProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.lastHeapFree = Math.max(0L, Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
        this.lastProcessCpuLoadPermille = sampleProcessCpuLoadPermille();
        this.pressure = classifyPressure(this.lastPlayers, this.lastMspt, this.lastHeapFree, this.lastProcessCpuLoadPermille);
        this.computedUnits = computeUnitsForPressure(this.pressure, this.lastAvailableProcessors, this.lastPlayers);
        refill(this.computedUnits);
    }

    public Grant claim(final Domain domain, final long cost, final boolean visibleCommit, final String reason) {
        final Domain safeDomain = domain == null ? Domain.PLAYER : domain;
        final long safeCost = Math.max(1L, cost);
        this.requested.get(safeDomain).incrementAndGet();
        if (!this.enabled || visibleCommit) {
            this.granted.get(safeDomain).incrementAndGet();
            return new Grant(true, Long.MAX_VALUE, "visible/ordered work is never resource-blocked: " + nullToEmpty(reason));
        }
        final AtomicLong bucket = this.tokens.get(safeDomain);
        while (true) {
            final long current = bucket.get();
            if (current < safeCost) {
                this.delayed.get(safeDomain).incrementAndGet();
                AGCSemanticInvariant.INSTANCE.record(
                    semanticDomain(safeDomain),
                    AGCSemanticInvariant.Decision.CONFLICT_SERIALISED,
                    "resource fair wait " + safeDomain + " cost=" + safeCost + " reason=" + nullToEmpty(reason)
                );
                return new Grant(false, current, "resource fair wait: " + safeDomain + " needs " + safeCost + " has " + current);
            }
            final long next = current - safeCost;
            if (bucket.compareAndSet(current, next)) {
                this.granted.get(safeDomain).incrementAndGet();
                return new Grant(true, next, "resource grant: " + safeDomain + " reason=" + nullToEmpty(reason));
            }
        }
    }

    public Pressure pressure() {
        return this.pressure;
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.tickSequence,
            this.targetPlayers,
            this.targetTps,
            this.reserveCpuPercent,
            this.maxMemoryPressureBytes,
            this.baseUnitsPerCore,
            this.maxCarryMultiplier,
            this.lastPlayers,
            this.lastWorlds,
            this.lastChunks,
            this.lastEntities,
            this.lastMspt,
            this.pressure,
            this.computedUnits,
            this.lastHeapFree,
            this.lastAvailableProcessors,
            this.lastProcessCpuLoadPermille,
            copyLongs(this.tokens),
            copyLongs(this.requested),
            copyLongs(this.granted),
            copyLongs(this.delayed),
            Collections.unmodifiableMap(new EnumMap<>(this.weights))
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCResourceEfficiencyEngine{enabled=" + snapshot.enabled()
            + ", targetPlayers=" + snapshot.targetPlayers()
            + ", targetTps=" + snapshot.targetTps()
            + ", pressure=" + snapshot.pressure()
            + ", tick=" + snapshot.tickSequence()
            + ", players=" + snapshot.players()
            + ", worlds=" + snapshot.worlds()
            + ", chunks=" + snapshot.chunks()
            + ", entities=" + snapshot.entities()
            + ", mspt=" + snapshot.mspt()
            + ", units=" + snapshot.computedUnits()
            + ", processors=" + snapshot.availableProcessors()
            + ", cpuLoadPermille=" + snapshot.processCpuLoadPermille()
            + ", heapFree=" + snapshot.heapFreeBytes()
            + ", remaining=" + snapshot.remaining()
            + ", delayed=" + snapshot.delayed()
            + '}';
    }

    private void refill(final long totalUnits) {
        long weightSum = 0L;
        for (final int weight : this.weights.values()) {
            weightSum += Math.max(1, weight);
        }
        final long safeTotal = Math.max(Domain.values().length, totalUnits);
        for (final Map.Entry<Domain, AtomicLong> entry : this.tokens.entrySet()) {
            final int weight = this.weights.getOrDefault(entry.getKey(), 1);
            final long refill = Math.max(1L, (safeTotal * Math.max(1, weight)) / Math.max(1L, weightSum));
            final long maxCarry = Math.max(refill, refill * this.maxCarryMultiplier);
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

    private Pressure classifyPressure(final int players, final double mspt, final long heapFree, final long cpuLoadPermille) {
        if (mspt >= 50.0D || heapFree <= this.maxMemoryPressureBytes / 3L || cpuLoadPermille >= 960L) {
            return Pressure.CRITICAL;
        }
        if (players >= this.targetPlayers || mspt >= 42.0D || heapFree <= this.maxMemoryPressureBytes || cpuLoadPermille >= 900L) {
            return Pressure.EXTREME;
        }
        if (players >= this.targetPlayers / 2 || mspt >= 32.0D || cpuLoadPermille >= 750L) {
            return Pressure.DENSE;
        }
        return Pressure.BASELINE;
    }

    private long computeUnitsForPressure(final Pressure pressure, final long processors, final int players) {
        final long reservedAdjustedCores = Math.max(1L, (processors * Math.max(10, 100 - this.reserveCpuPercent)) / 100L);
        long units = Math.max(16_384L, reservedAdjustedCores * this.baseUnitsPerCore);
        if (players > this.targetPlayers / 2) {
            units += Math.min(this.baseUnitsPerCore * processors, Math.max(0, players - this.targetPlayers / 2) * 64L);
        }
        return switch (pressure) {
            case BASELINE -> units;
            case DENSE -> Math.max(16_384L, (units * 4L) / 5L);
            case EXTREME -> Math.max(16_384L, (units * 2L) / 3L);
            case CRITICAL -> Math.max(16_384L, units / 2L);
        };
    }

    private static long sampleProcessCpuLoadPermille() {
        try {
            final Object mxBean = ManagementFactory.getOperatingSystemMXBean();
            final java.lang.reflect.Method method = mxBean.getClass().getMethod("getProcessCpuLoad");
            method.setAccessible(true);
            final Object value = method.invoke(mxBean);
            if (value instanceof Number number) {
                final double load = number.doubleValue();
                if (Double.isFinite(load) && load >= 0.0D) {
                    return Math.max(0L, Math.min(1000L, Math.round(load * 1000.0D)));
                }
            }
        } catch (final Throwable ignored) {
        }
        return -1L;
    }

    private static int defaultWeight(final Domain domain) {
        return switch (domain) {
            case NETWORK -> 18;
            case CHUNK -> 16;
            case ENTITY -> 16;
            case WORLD -> 12;
            case PLAYER -> 18;
            case MINIGAME -> 8;
            case PLUGIN -> 20;
            case IO -> 10;
        };
    }

    private static AGCSemanticInvariant.Domain semanticDomain(final Domain domain) {
        return switch (domain) {
            case NETWORK -> AGCSemanticInvariant.Domain.NETWORK;
            case CHUNK, IO -> AGCSemanticInvariant.Domain.CHUNK;
            case ENTITY -> AGCSemanticInvariant.Domain.ENTITY;
            case WORLD -> AGCSemanticInvariant.Domain.WORLD_TICK;
            case PLAYER, MINIGAME -> AGCSemanticInvariant.Domain.PLAYER;
            case PLUGIN -> AGCSemanticInvariant.Domain.PLUGIN;
        };
    }

    private static EnumMap<Domain, Long> copyLongs(final EnumMap<Domain, AtomicLong> source) {
        final EnumMap<Domain, Long> copy = new EnumMap<>(Domain.class);
        for (final Map.Entry<Domain, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    public record Grant(boolean admitted, long remainingUnits, String reason) {
    }

    public record Snapshot(
        boolean enabled,
        long tickSequence,
        int targetPlayers,
        double targetTps,
        int reserveCpuPercent,
        long maxMemoryPressureBytes,
        long baseUnitsPerCore,
        long maxCarryMultiplier,
        int players,
        int worlds,
        int chunks,
        int entities,
        double mspt,
        Pressure pressure,
        long computedUnits,
        long heapFreeBytes,
        long availableProcessors,
        long processCpuLoadPermille,
        Map<Domain, Long> remaining,
        Map<Domain, Long> requested,
        Map<Domain, Long> granted,
        Map<Domain, Long> delayed,
        Map<Domain, Integer> weights
    ) {
    }
}
