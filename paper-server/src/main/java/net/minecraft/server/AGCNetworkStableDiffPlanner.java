package net.minecraft.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Lossless packet diff-vector planner for alpha23. */
public final class AGCNetworkStableDiffPlanner {
    public static final AGCNetworkStableDiffPlanner INSTANCE = new AGCNetworkStableDiffPlanner();

    private final ConcurrentHashMap<Long, AtomicLong> shapeHits = new ConcurrentHashMap<>();
    private volatile long tickSequence;
    private volatile long planned;
    private volatile long orderedNow;
    private volatile long diffVectors;
    private volatile String lastReason = "cold";

    private AGCNetworkStableDiffPlanner() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.shapeHits.clear();
        this.planned = 0L;
        this.orderedNow = 0L;
        this.diffVectors = 0L;
        this.lastReason = "reset";
    }

    public Plan plan(final String packetName, final long recipients, final boolean interactive, final int deadlineTicks) {
        final long safeRecipients = Math.max(1L, recipients);
        final String safePacket = packetName == null || packetName.isBlank() ? "unknown" : packetName;
        final long key = stableKey(safePacket, safeRecipients, deadlineTicks);
        final long hits = this.shapeHits.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        if (interactive || deadlineTicks <= 0) {
            this.orderedNow++;
            this.lastReason = "interactive packet stays ordered: " + safePacket;
            return new Plan(false, true, key, 0, 0L, this.lastReason);
        }
        final int vectors = Math.max(1, Math.min(4096, (int) Math.sqrt(safeRecipients * 8.0D)));
        final long cost = Math.max(1L, vectors + (safeRecipients / 96L) + Math.min(64L, hits));
        this.planned++;
        this.diffVectors += vectors;
        this.lastReason = "stable diff vector plan packet=" + safePacket + " recipients=" + safeRecipients + " vectors=" + vectors;
        return new Plan(true, false, key, vectors, cost, this.lastReason);
    }

    public String statusLine() {
        return "AGCNetworkStableDiffPlanner{tick=" + this.tickSequence
            + ", planned=" + this.planned
            + ", orderedNow=" + this.orderedNow
            + ", diffVectors=" + this.diffVectors
            + ", shapeKeys=" + this.shapeHits.size()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final String packetName, final long recipients, final int deadlineTicks) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < packetName.length(); i++) {
            h ^= packetName.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= Long.rotateLeft(recipients, 17);
        h *= 0x100000001b3L;
        h ^= deadlineTicks;
        return h;
    }

    public record Plan(boolean batched, boolean orderedNow, long stableKey, int vectors, long cost, String reason) {}
}
