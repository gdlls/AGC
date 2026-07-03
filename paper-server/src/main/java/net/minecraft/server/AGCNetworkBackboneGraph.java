package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lossless backbone graph for high-fanout network planning.
 *
 * The graph is a send-plan spine/leaf model only. It never merges packet
 * instances, never drops packets and never reorders a connection stream.
 */
public final class AGCNetworkBackboneGraph {
    public static final AGCNetworkBackboneGraph INSTANCE = new AGCNetworkBackboneGraph();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong compiled = new AtomicLong();
    private final AtomicLong orderedNow = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastSpines;
    private volatile int lastLeaves;
    private volatile long lastBackboneKey;
    private volatile String lastReason = "cold";

    private AGCNetworkBackboneGraph() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "tick reset";
    }

    public Plan compile(final String packetName, final int recipients, final boolean interactive, final long pendingDeferredFlushes) {
        this.requested.incrementAndGet();
        final int safeRecipients = Math.max(1, recipients);
        if (interactive) {
            this.orderedNow.incrementAndGet();
            this.lastReason = "interactive packet stays immediately ordered: " + safe(packetName);
            return new Plan(false, true, 1, safeRecipients, 0L, this.lastReason);
        }
        final int leaves = Math.max(1, Math.min(4096, (safeRecipients + 31) / 32));
        final int spines = Math.max(1, Math.min(256, (leaves + 15) / 16));
        final long key = stableKey(packetName, spines, leaves, pendingDeferredFlushes);
        final AGCScale20LosslessPipeline.Grant grant = AGCScale20LosslessPipeline.INSTANCE.claim(
            AGCScale20LosslessPipeline.Stage.NETWORK_BACKBONE,
            Math.max(1L, spines * 4L + leaves + pendingDeferredFlushes / 2048L),
            "network-backbone " + safe(packetName)
        );
        this.lastSpines = spines;
        this.lastLeaves = leaves;
        this.lastBackboneKey = key;
        if (!grant.admitted()) {
            this.orderedNow.incrementAndGet();
            this.lastReason = grant.reason();
            return new Plan(false, true, spines, leaves, key, this.lastReason);
        }
        this.compiled.incrementAndGet();
        this.lastReason = "compiled backbone spines=" + spines + " leaves=" + leaves + " key=" + Long.toUnsignedString(key);
        return new Plan(true, false, spines, leaves, key, this.lastReason);
    }

    public String statusLine() {
        return "AGCNetworkBackboneGraph{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", compiled=" + this.compiled.get()
            + ", orderedNow=" + this.orderedNow.get()
            + ", lastSpines=" + this.lastSpines
            + ", lastLeaves=" + this.lastLeaves
            + ", lastBackboneKey=" + Long.toUnsignedString(this.lastBackboneKey)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final String packetName, final int spines, final int leaves, final long pending) {
        long hash = 0x6a09e667f3bcc909L;
        hash = mix(hash, packetName == null ? 0L : packetName.hashCode());
        hash = mix(hash, spines);
        hash = mix(hash, leaves);
        hash = mix(hash, pending);
        return hash;
    }

    private static long mix(final long hash, final long value) {
        long v = hash ^ (value + 0x9e3779b97f4a7c15L + (hash << 6) + (hash >>> 2));
        return v ^ (v >>> 33);
    }

    private static String safe(final String packetName) {
        return packetName == null || packetName.isBlank() ? "packet" : packetName;
    }

    public record Plan(boolean compiled, boolean orderedNow, int spines, int leaves, long backboneKey, String reason) {}
}
