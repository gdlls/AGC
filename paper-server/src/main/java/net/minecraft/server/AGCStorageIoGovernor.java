package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Storage/compression IO governor for scale work.
 * <p>
 * It budgets helper-side IO/compression preparation only. Chunk data ownership,
 * chunk status transitions and Bukkit-visible completion order remain controlled
 * by the existing chunk system.
 */
public final class AGCStorageIoGovernor {
    public static final AGCStorageIoGovernor INSTANCE = new AGCStorageIoGovernor();

    public enum Operation {
        CHUNK_READ_HINT,
        CHUNK_WRITE_HINT,
        PACKET_COMPRESSION,
        REGION_FILE_METADATA,
        PLAYERDATA_METADATA
    }

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong waited = new AtomicLong();

    private volatile boolean enabled = true;
    private volatile long tickSequence;
    private volatile long maxHintsPerTick = 32_768L;
    private volatile long usedHints;

    private AGCStorageIoGovernor() {
    }

    public void configure(final boolean enabled, final long maxHintsPerTick) {
        this.enabled = enabled;
        this.maxHintsPerTick = Math.max(1024L, maxHintsPerTick);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.usedHints = 0L;
    }

    public Admission admit(final Operation operation, final long estimatedCost, final String reason) {
        this.requests.incrementAndGet();
        if (!this.enabled) {
            this.admitted.incrementAndGet();
            return new Admission(true, Long.MAX_VALUE, "storage governor disabled");
        }
        final long safeCost = Math.max(1L, estimatedCost);
        if (this.usedHints + safeCost > this.maxHintsPerTick) {
            this.waited.incrementAndGet();
            return new Admission(false, this.maxHintsPerTick - this.usedHints, "storage hint budget wait: " + operation + ' ' + nullToEmpty(reason));
        }
        final AGCComputeTopologyPlanner.Admission compute = AGCComputeTopologyPlanner.INSTANCE.claim(
            operation == Operation.PACKET_COMPRESSION ? AGCComputeTopologyPlanner.Lane.COMPRESSION : AGCComputeTopologyPlanner.Lane.STORAGE_IO,
            safeCost,
            reason
        );
        if (!compute.admitted()) {
            this.waited.incrementAndGet();
            return new Admission(false, compute.remaining(), compute.reason());
        }
        this.usedHints += safeCost;
        this.admitted.incrementAndGet();
        return new Admission(true, this.maxHintsPerTick - this.usedHints, "storage/compression helper admitted");
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.tickSequence, this.maxHintsPerTick, this.usedHints, this.requests.get(), this.admitted.get(), this.waited.get());
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCStorageIoGovernor{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", maxHintsPerTick=" + snapshot.maxHintsPerTick()
            + ", usedHints=" + snapshot.usedHints()
            + ", requests=" + snapshot.requests()
            + ", admitted=" + snapshot.admitted()
            + ", waited=" + snapshot.waited()
            + '}';
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    public record Admission(boolean admitted, long remaining, String reason) {
    }

    public record Snapshot(boolean enabled, long tickSequence, long maxHintsPerTick, long usedHints, long requests, long admitted, long waited) {
    }
}
