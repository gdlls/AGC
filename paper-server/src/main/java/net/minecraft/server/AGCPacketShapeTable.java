package net.minecraft.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local packet-shape table. It shares the expensive classification and
 * fanout planning for packets that have the same semantic shape, but it never
 * shares mutable packet instances and never changes per-connection ordering.
 */
public final class AGCPacketShapeTable {
    public static final AGCPacketShapeTable INSTANCE = new AGCPacketShapeTable();

    public enum Lane {
        INTERACTIVE,
        CHUNK_LIGHT,
        COSMETIC,
        METADATA
    }

    private final ConcurrentHashMap<Integer, ShapeStats> shapes = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong shared = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int maxShapes = 524_288;
    private volatile int maxShapeFanout = 4096;
    private volatile long tickSequence;

    private AGCPacketShapeTable() {
    }

    public void configure(final boolean enabled, final int maxShapes, final int maxShapeFanout) {
        this.enabled = enabled;
        this.maxShapes = Math.max(1, maxShapes);
        this.maxShapeFanout = Math.max(1, maxShapeFanout);
    }

    public void beginTick(final long sequence) {
        if (sequence != this.tickSequence) {
            this.tickSequence = sequence;
            this.shapes.clear();
        }
    }

    public Decision plan(final String packetName, final AGCPacketBudget.Priority priority, final int estimatedBytes, final int receivers) {
        this.requests.incrementAndGet();
        final Lane lane = lane(priority);
        if (!this.enabled || lane == Lane.INTERACTIVE || lane == Lane.CHUNK_LIGHT) {
            this.ordered.incrementAndGet();
            return new Decision(false, true, lane, 0L, "interactive/chunk packet keeps immediate ordered send: " + safe(packetName));
        }
        if (this.shapes.size() >= this.maxShapes && !this.shapes.containsKey(hash(packetName, lane, estimatedBytes))) {
            this.evictions.incrementAndGet();
            this.ordered.incrementAndGet();
            return new Decision(false, true, lane, 0L, "shape table full; preserve ordered send: " + safe(packetName));
        }
        final int key = hash(packetName, lane, estimatedBytes);
        final ShapeStats stats = this.shapes.computeIfAbsent(key, ignored -> new ShapeStats(safe(packetName), lane, Math.max(1, estimatedBytes)));
        final long count = stats.hits.incrementAndGet();
        final int fanout = Math.max(1, Math.min(this.maxShapeFanout, receivers));
        final long cost = Math.max(1L, Math.max(1, estimatedBytes) / 256L + fanout / 32L);
        final AGCScaleControlPlane.Admission budget = AGCScaleControlPlane.INSTANCE.claim(AGCScaleControlPlane.Lane.PACKET_SHAPE, cost, packetName);
        if (!budget.admitted()) {
            this.ordered.incrementAndGet();
            return new Decision(false, true, lane, count, budget.reason());
        }
        this.shared.incrementAndGet();
        return new Decision(count > 1L, false, lane, count, "shape planned tick-locally without sharing mutable packet objects: " + safe(packetName));
    }

    public String statusLine() {
        return "AGCPacketShapeTable{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", shapes=" + this.shapes.size()
            + ", requests=" + this.requests.get()
            + ", shared=" + this.shared.get()
            + ", ordered=" + this.ordered.get()
            + ", evictions=" + this.evictions.get()
            + '}';
    }

    private static Lane lane(final AGCPacketBudget.Priority priority) {
        if (priority == AGCPacketBudget.Priority.MOVEMENT_COMBAT) {
            return Lane.INTERACTIVE;
        }
        if (priority == AGCPacketBudget.Priority.CHUNK_LIGHT) {
            return Lane.CHUNK_LIGHT;
        }
        if (priority == AGCPacketBudget.Priority.LOW_VALUE_METADATA) {
            return Lane.METADATA;
        }
        return Lane.COSMETIC;
    }

    private static int hash(final String packetName, final Lane lane, final int estimatedBytes) {
        int result = 17;
        result = 31 * result + safe(packetName).hashCode();
        result = 31 * result + lane.ordinal();
        result = 31 * result + Math.max(1, estimatedBytes) / 128;
        return result;
    }

    private static String safe(final String value) {
        return value == null ? "unknown" : value;
    }

    private static final class ShapeStats {
        private final String packetName;
        private final Lane lane;
        private final int bytes;
        private final AtomicLong hits = new AtomicLong();

        private ShapeStats(final String packetName, final Lane lane, final int bytes) {
            this.packetName = packetName;
            this.lane = lane;
            this.bytes = bytes;
        }
    }

    public record Decision(boolean sharedShape, boolean orderedNow, Lane lane, long shapeHits, String reason) {
    }
}
