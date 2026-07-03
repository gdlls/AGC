package net.minecraft.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local network fanout de-duplicator for thousands of players.
 * It shares packet-shape planning but never drops packets and never changes the
 * per-connection send order.
 */
public final class AGCNetworkFanoutKernel {
    public static final AGCNetworkFanoutKernel INSTANCE = new AGCNetworkFanoutKernel();

    public enum Decision {
        ORDERED_NOW,
        DEADLINE_BATCH
    }

    private final ConcurrentHashMap<Integer, AtomicLong> shapes = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong orderedNow = new AtomicLong();
    private final AtomicLong deadlineBatch = new AtomicLong();
    private final AtomicLong sharedShapes = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int maxShapes = 262_144;
    private volatile int maxDeadlineTicks = 1;
    private volatile long tickSequence;

    private AGCNetworkFanoutKernel() {
    }

    public void configure(final boolean enabled, final int maxShapes, final int maxDeadlineTicks) {
        this.enabled = enabled;
        this.maxShapes = Math.max(1024, maxShapes);
        this.maxDeadlineTicks = Math.max(0, maxDeadlineTicks);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.shapes.clear();
    }

    public Admission admit(final String packetName, final boolean interactive, final int recipients, final long pendingDeferred) {
        this.requests.incrementAndGet();
        if (!this.enabled || interactive || this.maxDeadlineTicks <= 0) {
            this.orderedNow.incrementAndGet();
            return new Admission(true, Decision.ORDERED_NOW, "interactive/order critical");
        }
        if (this.shapes.size() >= this.maxShapes) {
            this.orderedNow.incrementAndGet();
            return new Admission(true, Decision.ORDERED_NOW, "shape cap; preserve connection order");
        }
        final int shape = shape(packetName, recipients);
        final long repeats = this.shapes.computeIfAbsent(shape, ignored -> new AtomicLong()).incrementAndGet();
        if (repeats > 1L) {
            this.sharedShapes.incrementAndGet();
        }
        final long units = Math.max(1L, Math.max(1, recipients) / 8L + pendingDeferred / 512L);
        final AGCScaleKernel.Admission scale = AGCScaleKernel.INSTANCE.claim(AGCScaleKernel.Axis.NETWORK_FANOUT, units, packetName);
        if (!scale.admitted()) {
            this.orderedNow.incrementAndGet();
            return new Admission(true, Decision.ORDERED_NOW, scale.reason());
        }
        this.deadlineBatch.incrementAndGet();
        return new Admission(true, Decision.DEADLINE_BATCH, "deadline<=1tick shape=" + shape + " repeats=" + repeats);
    }

    public String statusLine() {
        return "AGCNetworkFanoutKernel{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", shapes=" + this.shapes.size()
            + ", requests=" + this.requests.get()
            + ", sharedShapes=" + this.sharedShapes.get()
            + ", deadlineBatch=" + this.deadlineBatch.get()
            + ", orderedNow=" + this.orderedNow.get()
            + '}';
    }

    private static int shape(final String packetName, final int recipients) {
        int h = packetName == null ? 0 : packetName.hashCode();
        h = 31 * h + Math.min(4096, Math.max(0, recipients));
        return h;
    }

    public record Admission(boolean admitted, Decision decision, String reason) {
    }
}
