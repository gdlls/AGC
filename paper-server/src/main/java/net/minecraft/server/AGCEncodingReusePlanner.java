package net.minecraft.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plans packet/chunk encoding helper reuse for large fanout bursts. It does not
 * reuse mutable Packet instances and does not bypass protocol libraries; it only
 * budgets immutable shape/compression helper work before ordered connection
 * writes occur.
 */
public final class AGCEncodingReusePlanner {
    public static final AGCEncodingReusePlanner INSTANCE = new AGCEncodingReusePlanner();

    private final ConcurrentHashMap<Integer, AtomicLong> encodings = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int maxShapes = 262_144;
    private volatile int compressionThresholdBytes = 512;
    private volatile long tickSequence;

    private AGCEncodingReusePlanner() {
    }

    public void configure(final boolean enabled, final int maxShapes, final int compressionThresholdBytes) {
        this.enabled = enabled;
        this.maxShapes = Math.max(1, maxShapes);
        this.compressionThresholdBytes = Math.max(0, compressionThresholdBytes);
    }

    public void beginTick(final long sequence) {
        if (sequence != this.tickSequence) {
            this.tickSequence = sequence;
            this.encodings.clear();
        }
    }

    public Decision plan(final String shape, final int estimatedBytes, final boolean protocolSensitive) {
        this.requests.incrementAndGet();
        if (!this.enabled || protocolSensitive || estimatedBytes < this.compressionThresholdBytes) {
            this.ordered.incrementAndGet();
            return new Decision(false, 0L, "encoding helper not shared for protocol-sensitive/small packet: " + safe(shape));
        }
        if (this.encodings.size() >= this.maxShapes) {
            this.ordered.incrementAndGet();
            return new Decision(false, 0L, "encoding shape table full; ordered encoding preserved");
        }
        final int key = 31 * safe(shape).hashCode() + estimatedBytes / 256;
        final long hits = this.encodings.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        final long cost = Math.max(1L, estimatedBytes / 512L);
        final AGCScaleControlPlane.Admission control = AGCScaleControlPlane.INSTANCE.claim(AGCScaleControlPlane.Lane.ENCODING_HELPER, cost, shape);
        if (!control.admitted()) {
            this.ordered.incrementAndGet();
            return new Decision(false, hits, control.reason());
        }
        this.planned.incrementAndGet();
        return new Decision(hits > 1L, hits, "immutable encoding helper planned; connection writes remain ordered");
    }

    public String statusLine() {
        return "AGCEncodingReusePlanner{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", shapes=" + this.encodings.size()
            + ", requests=" + this.requests.get()
            + ", planned=" + this.planned.get()
            + ", ordered=" + this.ordered.get()
            + '}';
    }

    private static String safe(final String value) {
        return value == null ? "unknown" : value;
    }

    public record Decision(boolean sharedEncoding, long shapeHits, String reason) {
    }
}
