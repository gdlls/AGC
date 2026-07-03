package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lossless multicast-shape graph for high fanout packet planning.
 *
 * This graph shares only immutable planning metadata. It never shares mutable
 * packet instances, never drops packets and never changes a connection's packet
 * order.
 */
public final class AGCNetworkMulticastShapeGraph {
    public static final AGCNetworkMulticastShapeGraph INSTANCE = new AGCNetworkMulticastShapeGraph();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong compiled = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastGroups;
    private volatile long lastShapeKey;
    private volatile String lastReason = "cold";

    private AGCNetworkMulticastShapeGraph() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastGroups = 0;
        this.lastReason = "tick reset";
    }

    public Plan compile(final String packetName, final int recipients, final boolean interactive, final long pendingDeferredFlushes) {
        this.requested.incrementAndGet();
        final int safeRecipients = Math.max(1, recipients);
        if (interactive) {
            this.ordered.incrementAndGet();
            this.lastReason = "interactive packet remains immediately ordered: " + safe(packetName);
            return new Plan(false, 1, true, 0L, this.lastReason);
        }
        final int groups = Math.max(1, Math.min(512, (safeRecipients + 47) / 48));
        final long shapeKey = stableShape(packetName, groups, pendingDeferredFlushes);
        final AGCScale19LogicKernel.Grant grant = AGCScale19LogicKernel.INSTANCE.claim(
            AGCScale19LogicKernel.Axis.NETWORK_GRAPH,
            Math.max(1L, groups + pendingDeferredFlushes / 1024L),
            "network-multicast-shape " + safe(packetName)
        );
        this.lastGroups = groups;
        this.lastShapeKey = shapeKey;
        if (!grant.admitted()) {
            this.ordered.incrementAndGet();
            this.lastReason = grant.reason();
            return new Plan(false, groups, true, shapeKey, this.lastReason);
        }
        this.compiled.incrementAndGet();
        this.lastReason = "lossless multicast shape groups=" + groups + " shapeKey=" + Long.toUnsignedString(shapeKey);
        return new Plan(true, groups, false, shapeKey, this.lastReason);
    }

    public String statusLine() {
        return "AGCNetworkMulticastShapeGraph{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", compiled=" + this.compiled.get()
            + ", ordered=" + this.ordered.get()
            + ", lastGroups=" + this.lastGroups
            + ", lastShapeKey=" + Long.toUnsignedString(this.lastShapeKey)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableShape(final String packetName, final int groups, final long pending) {
        long hash = 0x9e3779b97f4a7c15L;
        hash ^= (packetName == null ? 0 : packetName.hashCode()) * 0xbf58476d1ce4e5b9L;
        hash ^= ((long) groups << 32) ^ pending;
        return hash ^ (hash >>> 33);
    }

    private static String safe(final String packetName) {
        return packetName == null || packetName.isBlank() ? "packet" : packetName;
    }

    public record Plan(boolean compiled, int groups, boolean orderedNow, long shapeKey, String reason) {}
}
