package io.papermc.paper.agc.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Join-Storm Chunk Serialization Deduplicator (epoch-validated packet cache).
 *
 * <p>When several players join simultaneously (login storm) or cross a view-distance
 * boundary together, vanilla serializes the <b>same unchanged chunk</b> once per
 * recipient: {@code ClientboundLevelChunkPacketData} re-walks every section palette,
 * heightmap and block entity even though the bytes are identical for all recipients.
 * This engine caches that immutable payload between sends and rebuilds it only when
 * the chunk content actually changed.</p>
 *
 * <h2>Parity invariants</h2>
 * <ul>
 *   <li><b>Per-instance epoch key</b>: every {@code ChunkAccess} instance gets a unique
 *       epoch (global sequence) at construction; any content mutation bumps it. A cache
 *       hit requires an exact epoch match, so chunk unload→reload (new instance, new
 *       epoch) can never serve a stale payload, and even a cache-key collision between
 *       two different chunks is harmless (epochs differ → guaranteed miss).</li>
 *   <li><b>Block entities excluded</b>: entries are only cached when the chunk has zero
 *       block entities (fresh-generated terrain — the join-storm workload). BE-bearing
 *       chunks (villages, farms) always take the vanilla path, and BE add/remove bump
 *       the epoch anyway.</li>
 *   <li><b>Light is never cached</b>: {@code ClientboundLightUpdatePacketData} is
 *       rebuilt per send, exactly like vanilla.</li>
 *   <li><b>Anti-xray excluded</b>: only active when the level uses the
 *       {@code NO_OPERATION} packet block controller (anti-xray disabled), because
 *       {@code shouldModify} is per-player and would make the payload per-recipient.</li>
 *   <li><b>Per-connection packet object</b>: the cached payload is the immutable
 *       {@code ClientboundLevelChunkPacketData}; the outer
 *       {@code ClientboundLevelChunkWithLightPacket} is constructed fresh per send,
 *       so per-packet state (ready flag) is never shared between connections.</li>
 * </ul>
 *
 * <p>The payload stored is built by exactly the same constructor call vanilla uses
 * when {@code shouldModify == false}, so the serialized bytes are identical.</p>
 */
public final class AgcChunkSendCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcChunkSendCache.class);

    /** Max cached chunks. Each BE-less entry holds roughly 2-32 KiB. */
    private static final int MAX_ENTRIES = 1024;
    /** Soft byte budget (~24 MiB) to bound worst-case memory. */
    private static final long MAX_BYTES = 24L * 1024L * 1024L;

    private static final AgcChunkSendCache INSTANCE = new AgcChunkSendCache();

    public static AgcChunkSendCache get() {
        return INSTANCE;
    }

    private AgcChunkSendCache() {}

    private static final class Entry {
        final long contentEpoch;
        final Object payload; // ClientboundLevelChunkPacketData
        final long approxBytes;
        Entry(final long contentEpoch, final Object payload, final long approxBytes) {
            this.contentEpoch = contentEpoch;
            this.payload = payload;
            this.approxBytes = approxBytes;
        }
    }

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong staleDrops = new AtomicLong();
    private final AtomicLong bytesStored = new AtomicLong();

    /**
     * Looks up a cached payload for an unchanged chunk.
     *
     * @param cacheKey     world-scoped chunk key (call site mixes level identity + chunk pos)
     * @param contentEpoch live per-instance epoch from the chunk
     * @return the cached payload, or {@code null} on miss/stale
     */
    public Object getIfCurrent(final long cacheKey, final long contentEpoch) {
        final Entry entry = this.cache.get(cacheKey);
        if (entry == null) {
            return null;
        }
        // Epoch layout: high 32 bits = chunk instance id, low 32 bits = mutation count.
        final long entryInstance = entry.contentEpoch >>> 32;
        final long queryInstance = contentEpoch >>> 32;
        if (entryInstance != queryInstance) {
            // Different chunk instance: the caller's instance is the live one
            // (unload->reload or key collision). The entry is provably dead — drop it.
            this.cache.remove(cacheKey, entry);
            this.staleDrops.incrementAndGet();
            this.bytesStored.addAndGet(-entry.approxBytes);
            return null;
        }
        final long entryCount = entry.contentEpoch & 0xFFFFFFFFL;
        final long queryCount = contentEpoch & 0xFFFFFFFFL;
        if (entryCount == queryCount) {
            this.hits.incrementAndGet();
            return entry.payload;
        }
        if (entryCount < queryCount) {
            // Entry cached before a mutation the caller already observed: provably stale.
            this.cache.remove(cacheKey, entry);
            this.staleDrops.incrementAndGet();
            this.bytesStored.addAndGet(-entry.approxBytes);
            return null;
        }
        // entryCount > queryCount: the caller raced with a mutation and read a stale
        // epoch. The entry is NEWER than what the caller saw — keep it (the next
        // caller with the fresh epoch gets the hit); this caller just misses.
        this.misses.incrementAndGet();
        return null;
    }

    /**
     * Stores a freshly built payload.
     *
     * @param approxBytes caller-observed payload size (serialized buffer + heightmaps),
     *                    used only for the memory cap — correctness never depends on it
     */
    public void store(final long cacheKey, final long contentEpoch, final Object payload, final long approxBytes) {
        if (payload == null || approxBytes <= 0) {
            return;
        }
        if (approxBytes > MAX_BYTES) {
            return; // never cache pathological single entries
        }
        final Entry entry = new Entry(contentEpoch, payload, approxBytes);
        final Entry prev = this.cache.put(cacheKey, entry);
        if (prev != null) {
            this.bytesStored.addAndGet(approxBytes - prev.approxBytes);
        } else {
            this.bytesStored.addAndGet(approxBytes);
        }
        this.misses.incrementAndGet();
        this.evictIfNeeded();
    }

    private void evictIfNeeded() {
        if (this.cache.size() <= MAX_ENTRIES && this.bytesStored.get() <= MAX_BYTES) {
            return;
        }
        // Bounded sweep. Correctness never depends on eviction policy, only the memory cap does.
        int removed = 0;
        final var it = this.cache.entrySet().iterator();
        while (it.hasNext() && (this.cache.size() > MAX_ENTRIES || this.bytesStored.get() > MAX_BYTES)) {
            final Entry e = it.next().getValue();
            it.remove();
            this.bytesStored.addAndGet(-e.approxBytes);
            if (++removed >= 512) {
                break;
            }
        }
    }

    public void invalidateAll() {
        this.cache.clear();
        this.bytesStored.set(0);
    }

    public void resetMetrics() {
        this.hits.set(0);
        this.misses.set(0);
        this.staleDrops.set(0);
        this.bytesStored.set(0);
    }

    public void clear() {
        this.cache.clear();
        this.resetMetrics();
    }

    public CacheMetrics metrics() {
        return new CacheMetrics(
            this.cache.size(),
            this.hits.get(),
            this.misses.get(),
            this.staleDrops.get(),
            Math.max(0L, this.bytesStored.get())
        );
    }

    public record CacheMetrics(int entries, long hits, long misses, long staleDrops, long bytesStored) {
        public double hitRatio() {
            final long total = this.hits + this.misses;
            return total > 0 ? (double) this.hits / (double) total : 0.0;
        }
    }
}
