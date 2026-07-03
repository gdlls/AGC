package net.minecraft.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Lossless per-recipient order-vector planner for alpha24 network fanout. */
public final class AGCNetworkOrderVectorCompiler {
    public static final AGCNetworkOrderVectorCompiler INSTANCE = new AGCNetworkOrderVectorCompiler();

    private final ConcurrentHashMap<Long, AtomicLong> vectors = new ConcurrentHashMap<>();
    private volatile long tickSequence;
    private volatile long compiled;
    private volatile long orderedNow;
    private volatile String lastReason = "cold";

    private AGCNetworkOrderVectorCompiler() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.vectors.clear();
        this.compiled = 0L;
        this.orderedNow = 0L;
        this.lastReason = "reset";
    }

    public Plan compile(final String packetName, final long recipients, final boolean interactive, final int deadlineTicks) {
        final String packet = packetName == null || packetName.isBlank() ? "unknown" : packetName;
        final long safeRecipients = Math.max(1L, recipients);
        final long vectorKey = stableKey(packet, safeRecipients, deadlineTicks);
        if (interactive || deadlineTicks <= 0) {
            this.orderedNow++;
            this.lastReason = "interactive packet remains connection-ordered: " + packet;
            return new Plan(false, true, vectorKey, 0L, 0L, this.lastReason);
        }
        final long hit = this.vectors.computeIfAbsent(vectorKey, ignored -> new AtomicLong()).incrementAndGet();
        final long lanes = Math.max(1L, Math.min(8192L, (long) Math.ceil(Math.sqrt(safeRecipients * 4.0D))));
        final long cost = Math.max(1L, lanes + safeRecipients / 128L + Math.min(128L, hit));
        this.compiled++;
        this.lastReason = "compiled lossless network order vector packet=" + packet + " recipients=" + safeRecipients + " lanes=" + lanes;
        return new Plan(true, false, vectorKey, lanes, cost, this.lastReason);
    }

    public String statusLine() {
        return "AGCNetworkOrderVectorCompiler{tick=" + this.tickSequence
            + ", compiled=" + this.compiled
            + ", orderedNow=" + this.orderedNow
            + ", vectorKeys=" + this.vectors.size()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final String packet, final long recipients, final int deadlineTicks) {
        long h = 0x9e3779b97f4a7c15L;
        for (int i = 0; i < packet.length(); i++) {
            h ^= packet.charAt(i);
            h = Long.rotateLeft(h * 0xbf58476d1ce4e5b9L, 27);
        }
        h ^= Long.rotateLeft(recipients, 23);
        h ^= ((long) deadlineTicks << 48);
        return h;
    }

    public record Plan(boolean compiled, boolean orderedNow, long vectorKey, long lanes, long cost, String reason) {}
}
