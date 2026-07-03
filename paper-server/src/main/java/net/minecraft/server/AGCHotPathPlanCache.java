package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Tick-local compiled plan cache for alpha24 lossless helper work. */
public final class AGCHotPathPlanCache {
    public static final AGCHotPathPlanCache INSTANCE = new AGCHotPathPlanCache();

    public enum Kind {
        NETWORK_VECTOR,
        CHUNK_HORIZON,
        ENTITY_OBSERVER_SET,
        WORLD_BATCH_GRAPH,
        PLUGIN_TICKET,
        RESOURCE_SLOT
    }

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();
    private final EnumMap<Kind, AtomicLong> hits = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, AtomicLong> misses = new EnumMap<>(Kind.class);
    private volatile long tickSequence;
    private volatile long maxEntries = 262_144L;
    private volatile String lastReason = "cold";

    private AGCHotPathPlanCache() {
        for (final Kind kind : Kind.values()) {
            this.hits.put(kind, new AtomicLong());
            this.misses.put(kind, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.cache.clear();
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.maxEntries = Math.max(65_536L, processors * 65_536L);
        this.lastReason = "reset processors=" + processors;
    }

    public Plan compile(final Kind kind, final long stableKey, final long cost, final boolean visible, final String reason) {
        final Kind safeKind = kind == null ? Kind.RESOURCE_SLOT : kind;
        if (visible) {
            this.lastReason = "visible work bypasses hot-path cache: " + safe(reason);
            return new Plan(false, false, true, stableKey, Math.max(1L, cost), this.lastReason);
        }
        final long key = stableKey ^ (((long) safeKind.ordinal()) << 56) ^ this.tickSequence;
        final Entry cached = this.cache.get(key);
        if (cached != null) {
            this.hits.get(safeKind).incrementAndGet();
            this.lastReason = "hot-path plan cache hit kind=" + safeKind + " key=" + key;
            return new Plan(true, true, false, key, cached.cost(), this.lastReason);
        }
        if (this.cache.size() >= this.maxEntries) {
            this.lastReason = "hot-path cache saturated; helper waits without semantic change: " + safe(reason);
            return new Plan(false, false, false, key, Math.max(1L, cost), this.lastReason);
        }
        this.cache.put(key, new Entry(Math.max(1L, cost), safe(reason)));
        this.misses.get(safeKind).incrementAndGet();
        this.lastReason = "compiled new hot-path plan kind=" + safeKind + " key=" + key;
        return new Plan(true, false, false, key, Math.max(1L, cost), this.lastReason);
    }

    public String statusLine() {
        return "AGCHotPathPlanCache{tick=" + this.tickSequence
            + ", entries=" + this.cache.size()
            + ", maxEntries=" + this.maxEntries
            + ", hits=" + snapshot(this.hits)
            + ", misses=" + snapshot(this.misses)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static Map<Kind, Long> snapshot(final EnumMap<Kind, AtomicLong> source) {
        final EnumMap<Kind, Long> copy = new EnumMap<>(Kind.class);
        for (final Map.Entry<Kind, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    private record Entry(long cost, String reason) {}
    public record Plan(boolean compiled, boolean cacheHit, boolean orderedVisible, long key, long cost, String reason) {}
}
