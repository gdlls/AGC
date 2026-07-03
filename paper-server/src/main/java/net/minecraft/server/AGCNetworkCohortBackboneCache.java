package net.minecraft.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Tick-local packet cohort backbone cache; stores immutable planning keys only. */
public final class AGCNetworkCohortBackboneCache {
    public static final AGCNetworkCohortBackboneCache INSTANCE = new AGCNetworkCohortBackboneCache();

    private final LinkedHashMap<Long, Plan> cache = new LinkedHashMap<>(256, 0.75F, true);
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private volatile long tickSequence;
    private volatile int maxEntries = 16_384;
    private volatile String lastReason = "cold";

    private AGCNetworkCohortBackboneCache() {}

    public synchronized void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.cache.clear();
        this.maxEntries = 16_384;
        this.lastReason = "reset maxEntries=" + this.maxEntries;
    }

    public synchronized Plan plan(final String packetName, final long recipients, final boolean interactive, final int deadlineTicks) {
        final long safeRecipients = Math.max(1L, recipients);
        final long key = mix(packetName == null ? 0 : packetName.hashCode(), safeRecipients, interactive ? 1 : 0, deadlineTicks);
        final Plan cached = this.cache.get(key);
        if (cached != null) {
            this.hits.incrementAndGet();
            this.lastReason = "reuse packet cohort backbone key=" + Long.toUnsignedString(key);
            return cached.withReason(this.lastReason);
        }
        this.misses.incrementAndGet();
        final boolean immediate = interactive || deadlineTicks <= 0;
        final int cohorts = Math.max(1, Math.min(8192, (int) ((safeRecipients + 31L) / 32L)));
        final int backboneNodes = Math.max(1, Math.min(1024, (cohorts + 15) / 16));
        final long cost = Math.max(1L, cohorts + backboneNodes);
        this.lastReason = (immediate ? "ordered" : "lossless") + " packet cohort backbone cohorts=" + cohorts + " nodes=" + backboneNodes;
        final Plan plan = new Plan(!immediate, immediate, cohorts, backboneNodes, cost, key, this.lastReason);
        if (this.cache.size() >= this.maxEntries) {
            final Long first = this.cache.keySet().iterator().next();
            this.cache.remove(first);
        }
        this.cache.put(key, plan);
        return plan;
    }

    public String statusLine() {
        return "AGCNetworkCohortBackboneCache{tick=" + this.tickSequence
            + ", entries=" + this.cache.size()
            + ", maxEntries=" + this.maxEntries
            + ", hits=" + this.hits.get()
            + ", misses=" + this.misses.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long mix(final long a, final long b, final long c, final long d) {
        long x = 0x9E3779B97F4A7C15L;
        x ^= a + 0x9E3779B97F4A7C15L + (x << 6) + (x >>> 2);
        x ^= b + 0xBF58476D1CE4E5B9L + (x << 6) + (x >>> 2);
        x ^= c + 0x94D049BB133111EBL + (x << 6) + (x >>> 2);
        x ^= d + 0xD6E8FEB86659FD93L + (x << 6) + (x >>> 2);
        return x;
    }

    public record Plan(boolean planned, boolean orderedNow, int cohorts, int backboneNodes, long cost, long stableKey, String reason) {
        Plan withReason(final String reason) {
            return new Plan(this.planned, this.orderedNow, this.cohorts, this.backboneNodes, this.cost, this.stableKey, reason);
        }
    }
}
