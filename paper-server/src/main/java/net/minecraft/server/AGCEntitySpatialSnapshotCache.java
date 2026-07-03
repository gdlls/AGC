package net.minecraft.server;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tick read-only entity snapshot cache for dense player/entity scenes.
 * <p>
 * It stores only caller-provided immutable/read-only summaries. It never owns
 * live entity state, never suppresses entity ticks, and expires entries on tick
 * boundaries so plugin-visible state remains authoritative.
 */
public final class AGCEntitySpatialSnapshotCache {
    public static final AGCEntitySpatialSnapshotCache INSTANCE = new AGCEntitySpatialSnapshotCache();

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int maxEntries = 262_144;
    private volatile long tickSequence;

    private AGCEntitySpatialSnapshotCache() {
    }

    public void configure(final boolean enabled, final int maxEntries) {
        this.enabled = enabled;
        this.maxEntries = Math.max(1024, maxEntries);
    }

    public void beginTick(final long tickSequence) {
        this.tickSequence = Math.max(0L, tickSequence);
        if (this.entries.size() > this.maxEntries) {
            this.entries.clear();
        }
    }

    public Object getOrCompute(final UUID playerId, final long sectionKey, final int viewRevision, final java.util.function.Supplier<Object> readOnlySupplier) {
        this.requests.incrementAndGet();
        if (!this.enabled || playerId == null || readOnlySupplier == null) {
            this.misses.incrementAndGet();
            return readOnlySupplier == null ? null : readOnlySupplier.get();
        }
        final AGCTickBudgetArbiter.Admission budget = AGCTickBudgetArbiter.INSTANCE.claim(AGCTickBudgetArbiter.Category.ENTITY_SNAPSHOT, 1L, false);
        if (!budget.admitted()) {
            this.misses.incrementAndGet();
            return readOnlySupplier.get();
        }
        final Key key = new Key(playerId, sectionKey, viewRevision, this.tickSequence);
        final Entry existing = this.entries.get(key);
        if (existing != null) {
            this.hits.incrementAndGet();
            return existing.value();
        }
        this.misses.incrementAndGet();
        final Object value = readOnlySupplier.get();
        if (this.entries.size() < this.maxEntries) {
            this.entries.putIfAbsent(key, new Entry(value));
        }
        return value;
    }

    public void forget(final UUID playerId) {
        if (playerId == null) {
            return;
        }
        this.entries.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.tickSequence, this.maxEntries, this.entries.size(), this.requests.get(), this.hits.get(), this.misses.get());
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCEntitySpatialSnapshotCache{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", entries=" + snapshot.entries()
            + '/' + snapshot.maxEntries()
            + ", requests=" + snapshot.requests()
            + ", hits=" + snapshot.hits()
            + ", misses=" + snapshot.misses()
            + '}';
    }

    private record Key(UUID playerId, long sectionKey, int viewRevision, long tickSequence) {
        private Key {
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    private record Entry(Object value) {
    }

    public record Snapshot(boolean enabled, long tickSequence, int maxEntries, int entries, long requests, long hits, long misses) {
    }
}
