package net.minecraft.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local read-only fanout de-duplication for large survival servers.
 * <p>
 * It records that multiple players/cells need the same derived fanout plan so
 * the expensive selection can be prepared once and replayed through the normal
 * ordered packet/entity/chunk paths. It never drops packets and never changes
 * packet order visible to a connection.
 */
public final class AGCFanoutDeduplicator {
    public static final AGCFanoutDeduplicator INSTANCE = new AGCFanoutDeduplicator();

    public enum Channel {
        CHUNK_INTEREST,
        ENTITY_TRACKER,
        COSMETIC_PACKET,
        LIGHT_UPDATE,
        TABLIST_METADATA
    }

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong sharedHits = new AtomicLong();
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong denied = new AtomicLong();

    private volatile boolean enabled = true;
    private volatile int maxEntries = 524_288;
    private volatile long tickSequence;

    private AGCFanoutDeduplicator() {
    }

    public void configure(final boolean enabled, final int maxEntries) {
        this.enabled = enabled;
        this.maxEntries = Math.max(1024, maxEntries);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.entries.clear();
    }

    public Decision admit(final Channel channel, final long cellKey, final String signature, final long estimatedCost) {
        this.requests.incrementAndGet();
        if (!this.enabled) {
            return new Decision(true, false, 0L, "fanout dedupe disabled");
        }
        if (this.entries.size() >= this.maxEntries) {
            this.denied.incrementAndGet();
            return new Decision(false, false, 0L, "fanout table full; preserve ordered direct fanout");
        }
        final long safeCost = Math.max(1L, estimatedCost);
        final AGCComputeTopologyPlanner.Admission compute = AGCComputeTopologyPlanner.INSTANCE.claim(
            AGCComputeTopologyPlanner.Lane.FANOUT_DEDUP,
            safeCost,
            signature
        );
        if (!compute.admitted()) {
            this.denied.incrementAndGet();
            return new Decision(false, false, compute.remaining(), compute.reason());
        }
        final Key key = new Key(channel == null ? Channel.COSMETIC_PACKET : channel, cellKey, signature == null ? "" : signature, this.tickSequence);
        final Entry existing = this.entries.putIfAbsent(key, new Entry(safeCost));
        if (existing == null) {
            this.created.incrementAndGet();
            return new Decision(true, false, compute.remaining(), "created shared fanout plan");
        }
        existing.hits.incrementAndGet();
        this.sharedHits.incrementAndGet();
        return new Decision(true, true, compute.remaining(), "reused shared fanout plan hits=" + existing.hits.get());
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.tickSequence, this.entries.size(), this.maxEntries, this.requests.get(), this.created.get(), this.sharedHits.get(), this.denied.get());
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCFanoutDeduplicator{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", entries=" + snapshot.entries()
            + ", maxEntries=" + snapshot.maxEntries()
            + ", requests=" + snapshot.requests()
            + ", created=" + snapshot.created()
            + ", sharedHits=" + snapshot.sharedHits()
            + ", denied=" + snapshot.denied()
            + '}';
    }

    private record Key(Channel channel, long cellKey, String signature, long tick) {
    }

    private static final class Entry {
        private final long cost;
        private final AtomicLong hits = new AtomicLong(1L);

        private Entry(final long cost) {
            this.cost = cost;
        }
    }

    public record Decision(boolean admitted, boolean shared, long remaining, String reason) {
    }

    public record Snapshot(boolean enabled, long tickSequence, int entries, int maxEntries, long requests, long created, long sharedHits, long denied) {
    }
}
