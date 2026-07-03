package net.minecraft.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deficit-style fairness layer shared by survival-scale AGC lanes.
 * <p>
 * It prevents one player, world or arena hotspot from consuming all read-only
 * preparation budget while preserving FIFO/ordered commit semantics in the
 * underlying domain-specific queues.
 */
public final class AGCGlobalFairnessMatrix {
    public static final AGCGlobalFairnessMatrix INSTANCE = new AGCGlobalFairnessMatrix();

    public enum Scope {
        PLAYER,
        WORLD,
        ARENA,
        REGION,
        GLOBAL
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong waited = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();

    private volatile boolean enabled = true;
    private volatile long playerQuantum = 512L;
    private volatile long worldQuantum = 4096L;
    private volatile long arenaQuantum = 2048L;
    private volatile long regionQuantum = 1024L;
    private volatile long maxCarryMultiplier = 4L;
    private volatile long tickSequence;

    private AGCGlobalFairnessMatrix() {
    }

    public void configure(
        final boolean enabled,
        final long playerQuantum,
        final long worldQuantum,
        final long arenaQuantum,
        final long regionQuantum,
        final long maxCarryMultiplier
    ) {
        this.enabled = enabled;
        this.playerQuantum = Math.max(1L, playerQuantum);
        this.worldQuantum = Math.max(1L, worldQuantum);
        this.arenaQuantum = Math.max(1L, arenaQuantum);
        this.regionQuantum = Math.max(1L, regionQuantum);
        this.maxCarryMultiplier = Math.max(1L, Math.min(32L, maxCarryMultiplier));
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        for (final Map.Entry<String, Bucket> entry : this.buckets.entrySet()) {
            final Bucket bucket = entry.getValue();
            bucket.refill(quantum(bucket.scope));
        }
        if (this.buckets.size() > 262_144) {
            this.buckets.entrySet().removeIf(entry -> entry.getValue().lastUsedTick + 200L < this.tickSequence);
        }
    }

    public Admission admitPlayer(final UUID playerId, final long cost, final String reason) {
        return this.admit(Scope.PLAYER, playerId == null ? "unknown" : playerId.toString(), cost, reason);
    }

    public Admission admitWorld(final String worldKey, final long cost, final String reason) {
        return this.admit(Scope.WORLD, worldKey == null ? "unknown" : worldKey, cost, reason);
    }

    public Admission admit(final Scope scope, final String key, final long cost, final String reason) {
        this.requests.incrementAndGet();
        if (!this.enabled) {
            this.admitted.incrementAndGet();
            return new Admission(true, Long.MAX_VALUE, "fairness disabled: " + nullToEmpty(reason));
        }
        final Scope safeScope = scope == null ? Scope.GLOBAL : scope;
        final String safeKey = safeScope.name() + ':' + (key == null ? "global" : key);
        final Bucket bucket = this.buckets.computeIfAbsent(safeKey, ignored -> new Bucket(safeScope, quantum(safeScope), this.tickSequence));
        final long safeCost = Math.max(1L, cost);
        final long remaining = bucket.consume(safeCost, quantum(safeScope), this.maxCarryMultiplier, this.tickSequence);
        if (remaining >= 0L) {
            this.admitted.incrementAndGet();
            return new Admission(true, remaining, "fairness grant: " + safeScope + ' ' + nullToEmpty(reason));
        }
        this.waited.incrementAndGet();
        AGCSemanticInvariant.INSTANCE.record(
            safeScope == Scope.WORLD ? AGCSemanticInvariant.Domain.WORLD_TICK : AGCSemanticInvariant.Domain.PLAYER,
            AGCSemanticInvariant.Decision.CONFLICT_SERIALISED,
            "global fairness wait scope=" + safeScope + " key=" + safeKey + " reason=" + nullToEmpty(reason)
        );
        return new Admission(false, bucket.tokens.get(), "fairness wait: " + safeScope + " cost=" + safeCost + " reason=" + nullToEmpty(reason));
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.tickSequence,
            this.buckets.size(),
            this.playerQuantum,
            this.worldQuantum,
            this.arenaQuantum,
            this.regionQuantum,
            this.maxCarryMultiplier,
            this.requests.get(),
            this.admitted.get(),
            this.waited.get()
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCGlobalFairnessMatrix{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", buckets=" + snapshot.buckets()
            + ", playerQuantum=" + snapshot.playerQuantum()
            + ", worldQuantum=" + snapshot.worldQuantum()
            + ", arenaQuantum=" + snapshot.arenaQuantum()
            + ", regionQuantum=" + snapshot.regionQuantum()
            + ", requests=" + snapshot.requests()
            + ", admitted=" + snapshot.admitted()
            + ", waited=" + snapshot.waited()
            + '}';
    }

    private long quantum(final Scope scope) {
        return switch (scope) {
            case PLAYER -> this.playerQuantum;
            case WORLD -> this.worldQuantum;
            case ARENA -> this.arenaQuantum;
            case REGION -> this.regionQuantum;
            case GLOBAL -> Math.max(this.playerQuantum, this.regionQuantum);
        };
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    private static final class Bucket {
        private final Scope scope;
        private final AtomicLong tokens = new AtomicLong();
        private volatile long lastUsedTick;

        Bucket(final Scope scope, final long quantum, final long tick) {
            this.scope = scope;
            this.tokens.set(Math.max(1L, quantum));
            this.lastUsedTick = tick;
        }

        void refill(final long quantum) {
            final long refill = Math.max(1L, quantum);
            while (true) {
                final long current = this.tokens.get();
                final long next = Math.min(refill * 8L, current + refill);
                if (this.tokens.compareAndSet(current, next)) {
                    return;
                }
            }
        }

        long consume(final long cost, final long quantum, final long maxCarryMultiplier, final long tick) {
            this.lastUsedTick = tick;
            final long maxCarry = Math.max(quantum, quantum * Math.max(1L, maxCarryMultiplier));
            while (true) {
                final long actual = this.tokens.get();
                final long current = Math.min(maxCarry, actual);
                if (current < cost) {
                    return -1L;
                }
                final long next = current - cost;
                if (this.tokens.compareAndSet(actual, next)) {
                    return next;
                }
            }
        }
    }

    public record Admission(boolean admitted, long remaining, String reason) {
    }

    public record Snapshot(
        boolean enabled,
        long tickSequence,
        int buckets,
        long playerQuantum,
        long worldQuantum,
        long arenaQuantum,
        long regionQuantum,
        long maxCarryMultiplier,
        long requests,
        long admitted,
        long waited
    ) {
    }
}
