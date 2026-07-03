package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central tick budget allocator for AGC aggressive mode.
 * <p>
 * The arbiter is intentionally semantic-preserving: it never authorises packet
 * drops, chunk queue reordering, skipped entity AI, or off-thread Bukkit
 * mutation. It only decides whether a non-visible prepare/batch lane has budget
 * this tick. Visible commits still run through ordered lanes.
 */
public final class AGCTickBudgetArbiter {
    public static final AGCTickBudgetArbiter INSTANCE = new AGCTickBudgetArbiter();

    public enum Category {
        NETWORK_DEADLINE,
        CHUNK_SEND,
        CHUNK_LOAD,
        CHUNK_GENERATE,
        ENTITY_SNAPSHOT,
        WORLD_PREPARE,
        PLAYER_FLOW,
        MINIGAME_PREWARM,
        PLUGIN_COMMIT
    }

    private final EnumMap<Category, Bucket> buckets = new EnumMap<>(Category.class);
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong waited = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile long baseUnitsPerTick = 250_000L;
    private volatile long minUnitsPerCategory = 256L;
    private volatile int hardPressureDivisor = 4;
    private volatile long tickSequence;
    private volatile AGCPerformanceStandard.Pressure pressure = AGCPerformanceStandard.Pressure.NORMAL;

    private AGCTickBudgetArbiter() {
        for (final Category category : Category.values()) {
            this.buckets.put(category, new Bucket(defaultWeight(category)));
        }
    }

    public void configure(final boolean enabled, final long baseUnitsPerTick, final long minUnitsPerCategory, final int hardPressureDivisor) {
        this.enabled = enabled;
        this.baseUnitsPerTick = Math.max(1024L, baseUnitsPerTick);
        this.minUnitsPerCategory = Math.max(1L, minUnitsPerCategory);
        this.hardPressureDivisor = Math.max(1, hardPressureDivisor);
    }

    public void beginTick(final long tickSequence, final AGCPerformanceStandard.Pressure pressure) {
        this.tickSequence = Math.max(0L, tickSequence);
        this.pressure = pressure == null ? AGCPerformanceStandard.Pressure.NORMAL : pressure;
        final long scaledBase = switch (this.pressure) {
            case NORMAL -> this.baseUnitsPerTick;
            case SOFT -> Math.max(this.minUnitsPerCategory * Category.values().length, this.baseUnitsPerTick / 2L);
            case HARD, INVARIANT_GUARD -> Math.max(this.minUnitsPerCategory * Category.values().length, this.baseUnitsPerTick / this.hardPressureDivisor);
        };
        long totalWeight = 0L;
        for (final Bucket bucket : this.buckets.values()) {
            totalWeight += Math.max(1, bucket.weight);
        }
        for (final Map.Entry<Category, Bucket> entry : this.buckets.entrySet()) {
            final Bucket bucket = entry.getValue();
            final long refill = Math.max(this.minUnitsPerCategory, scaledBase * Math.max(1, bucket.weight) / Math.max(1L, totalWeight));
            bucket.refill(refill);
        }
    }

    public Admission claim(final Category category, final long costUnits, final boolean visibleOrderedCommit) {
        this.requests.incrementAndGet();
        if (!this.enabled || category == null || visibleOrderedCommit || category == Category.PLUGIN_COMMIT) {
            this.admitted.incrementAndGet();
            return new Admission(true, Integer.MAX_VALUE, "ordered visible commit");
        }
        final Bucket bucket = this.buckets.get(category);
        if (bucket == null) {
            this.admitted.incrementAndGet();
            return new Admission(true, Integer.MAX_VALUE, "untracked category");
        }
        final long cost = Math.max(1L, costUnits);
        final long remaining = bucket.tryConsume(cost);
        if (remaining < 0L) {
            this.waited.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(
                semanticDomain(category),
                AGCSemanticInvariant.Decision.CONFLICT_SERIALISED,
                "tick-budget wait category=" + category + " cost=" + cost
            );
            return new Admission(false, bucket.peek(), "tick budget exhausted");
        }
        this.admitted.incrementAndGet();
        return new Admission(true, remaining, "tick budget admitted");
    }

    public void setWeight(final Category category, final int weight) {
        if (category != null) {
            final Bucket bucket = this.buckets.get(category);
            if (bucket != null) {
                bucket.weight = Math.max(1, weight);
            }
        }
    }

    public Snapshot snapshot() {
        final EnumMap<Category, Long> remaining = new EnumMap<>(Category.class);
        final EnumMap<Category, Integer> weights = new EnumMap<>(Category.class);
        for (final Map.Entry<Category, Bucket> entry : this.buckets.entrySet()) {
            remaining.put(entry.getKey(), entry.getValue().peek());
            weights.put(entry.getKey(), entry.getValue().weight);
        }
        return new Snapshot(
            this.enabled,
            this.tickSequence,
            this.baseUnitsPerTick,
            this.minUnitsPerCategory,
            this.hardPressureDivisor,
            this.pressure,
            this.requests.get(),
            this.admitted.get(),
            this.waited.get(),
            Map.copyOf(remaining),
            Map.copyOf(weights)
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCTickBudgetArbiter{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", pressure=" + snapshot.pressure()
            + ", baseUnitsPerTick=" + snapshot.baseUnitsPerTick()
            + ", requests=" + snapshot.requests()
            + ", admitted=" + snapshot.admitted()
            + ", waited=" + snapshot.waited()
            + ", remaining=" + snapshot.remaining()
            + ", weights=" + snapshot.weights()
            + '}';
    }

    private static int defaultWeight(final Category category) {
        return switch (category) {
            case NETWORK_DEADLINE -> 16;
            case CHUNK_SEND -> 12;
            case CHUNK_LOAD -> 10;
            case CHUNK_GENERATE -> 4;
            case ENTITY_SNAPSHOT -> 14;
            case WORLD_PREPARE -> 8;
            case PLAYER_FLOW -> 12;
            case MINIGAME_PREWARM -> 6;
            case PLUGIN_COMMIT -> 20;
        };
    }

    private static AGCSemanticInvariant.Domain semanticDomain(final Category category) {
        return switch (category) {
            case NETWORK_DEADLINE -> AGCSemanticInvariant.Domain.NETWORK;
            case CHUNK_SEND, CHUNK_LOAD, CHUNK_GENERATE -> AGCSemanticInvariant.Domain.CHUNK;
            case ENTITY_SNAPSHOT -> AGCSemanticInvariant.Domain.ENTITY;
            case WORLD_PREPARE -> AGCSemanticInvariant.Domain.WORLD_TICK;
            case PLAYER_FLOW -> AGCSemanticInvariant.Domain.PLAYER;
            case MINIGAME_PREWARM -> AGCSemanticInvariant.Domain.PLAYER;
            case PLUGIN_COMMIT -> AGCSemanticInvariant.Domain.PLUGIN;
        };
    }

    private static final class Bucket {
        private final AtomicLong tokens = new AtomicLong();
        private volatile int weight;
        private volatile long maxCarry;

        Bucket(final int weight) {
            this.weight = Math.max(1, weight);
        }

        void refill(final long amount) {
            final long refill = Math.max(1L, amount);
            this.maxCarry = Math.max(refill, refill * 3L);
            while (true) {
                final long current = this.tokens.get();
                final long next = Math.min(this.maxCarry, current + refill);
                if (this.tokens.compareAndSet(current, next)) {
                    return;
                }
            }
        }

        long tryConsume(final long cost) {
            while (true) {
                final long current = this.tokens.get();
                if (current < cost) {
                    return -1L;
                }
                final long next = current - cost;
                if (this.tokens.compareAndSet(current, next)) {
                    return next;
                }
            }
        }

        long peek() {
            return this.tokens.get();
        }
    }

    public record Admission(boolean admitted, long remainingUnits, String reason) {
    }

    public record Snapshot(
        boolean enabled,
        long tickSequence,
        long baseUnitsPerTick,
        long minUnitsPerCategory,
        int hardPressureDivisor,
        AGCPerformanceStandard.Pressure pressure,
        long requests,
        long admitted,
        long waited,
        Map<Category, Long> remaining,
        Map<Category, Integer> weights
    ) {
    }
}
