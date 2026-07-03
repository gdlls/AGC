package net.minecraft.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk intent planner for thousand-player survival scale.
 * <p>
 * This is not a chunk loader and does not reorder the Paper chunk queues. It
 * records stable FIFO intent keys and only budgets read-only planning around
 * them, so the later load/generate/send commit still happens in existing order.
 */
public final class AGCChunkIntentPlanner {
    public static final AGCChunkIntentPlanner INSTANCE = new AGCChunkIntentPlanner();

    private final ConcurrentHashMap<String, AtomicLong> intentsByOwner = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();

    private volatile boolean enabled = true;
    private volatile int maxOwners = 262_144;
    private volatile long tickSequence;

    private AGCChunkIntentPlanner() {
    }

    public void configure(final boolean enabled, final int maxOwners) {
        this.enabled = enabled;
        this.maxOwners = Math.max(1024, maxOwners);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        if (this.intentsByOwner.size() > this.maxOwners) {
            this.intentsByOwner.clear();
        }
    }

    public Admission plan(final UUID playerId, final int chunkX, final int chunkZ, final AGCChunkBudget.Operation operation) {
        this.requests.incrementAndGet();
        if (!this.enabled) {
            this.planned.incrementAndGet();
            return new Admission(true, 0L, "chunk intent planner disabled; direct FIFO intent");
        }
        final String owner = playerId == null ? "unknown" : playerId.toString();
        final long regionKey = regionKey(chunkX, chunkZ);
        final AGCGlobalFairnessMatrix.Admission fairness = AGCGlobalFairnessMatrix.INSTANCE.admit(
            AGCGlobalFairnessMatrix.Scope.REGION,
            Long.toString(regionKey),
            1L,
            operation == null ? "chunk intent" : operation.name()
        );
        if (!fairness.admitted()) {
            this.waits.incrementAndGet();
            return new Admission(false, fairness.remaining(), fairness.reason());
        }
        final AGCComputeTopologyPlanner.Admission compute = AGCComputeTopologyPlanner.INSTANCE.claim(
            AGCComputeTopologyPlanner.Lane.CHUNK_INTENT,
            1L,
            operation == null ? "chunk intent" : operation.name()
        );
        if (!compute.admitted()) {
            this.waits.incrementAndGet();
            return new Admission(false, compute.remaining(), compute.reason());
        }
        this.intentsByOwner.computeIfAbsent(owner, ignored -> new AtomicLong()).incrementAndGet();
        this.planned.incrementAndGet();
        return new Admission(true, compute.remaining(), "chunk intent planned owner=" + owner + " region=" + regionKey);
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.tickSequence, this.intentsByOwner.size(), this.requests.get(), this.planned.get(), this.waits.get());
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCChunkIntentPlanner{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", owners=" + snapshot.owners()
            + ", requests=" + snapshot.requests()
            + ", planned=" + snapshot.planned()
            + ", waits=" + snapshot.waits()
            + '}';
    }

    private static long regionKey(final int chunkX, final int chunkZ) {
        final long rx = Math.floorDiv(chunkX, 8);
        final long rz = Math.floorDiv(chunkZ, 8);
        return (rx << 32) ^ (rz & 0xFFFF_FFFFL);
    }

    public record Admission(boolean admitted, long remaining, String reason) {
    }

    public record Snapshot(boolean enabled, long tickSequence, int owners, long requests, long planned, long waits) {
    }
}
