package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Chunk pipeline planner that preserves Paper's chunk state ownership and FIFO order. */
public final class AGCChunkPipelineKernel {
    public static final AGCChunkPipelineKernel INSTANCE = new AGCChunkPipelineKernel();

    private final ConcurrentHashMap<UUID, AtomicLong> perPlayerTickets = new ConcurrentHashMap<>();
    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private final AtomicLong ioHints = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile long perPlayerQuantum = 96L;
    private volatile long tickSequence;

    private AGCChunkPipelineKernel() {
    }

    public void configure(final boolean enabled, final long perPlayerQuantum) {
        this.enabled = enabled;
        this.perPlayerQuantum = Math.max(1L, perPlayerQuantum);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        for (final AtomicLong tickets : this.perPlayerTickets.values()) {
            tickets.set(Math.min(this.perPlayerQuantum * 4L, tickets.get() + this.perPlayerQuantum));
        }
    }

    public Admission admit(final UUID playerId, final AGCChunkBudget.Operation operation, final String worldKey, final int chunkX, final int chunkZ) {
        if (!this.enabled || playerId == null || operation == null) {
            this.planned.incrementAndGet();
            return new Admission(true, "ordered chunk pipeline");
        }
        final AtomicLong tickets = this.perPlayerTickets.computeIfAbsent(playerId, ignored -> new AtomicLong(this.perPlayerQuantum));
        final long cost = switch (operation) {
            case SEND -> 1L;
            case LOAD -> 3L;
            case GENERATE -> 8L;
        };
        while (true) {
            final long current = tickets.get();
            if (current < cost) {
                this.waits.incrementAndGet();
                return new Admission(false, "FIFO head waits for per-player chunk token");
            }
            if (tickets.compareAndSet(current, current - cost)) {
                break;
            }
        }
        final AGCRegionHotspotMap.Observation observation = AGCRegionHotspotMap.INSTANCE.observe(worldKey, chunkX << 4, chunkZ << 4, 1, 1, 0);
        final AGCScaleKernel.Admission scale = AGCScaleKernel.INSTANCE.claim(AGCScaleKernel.Axis.CHUNK_PIPELINE, cost, operation.name());
        if (!scale.admitted()) {
            this.waits.incrementAndGet();
            return new Admission(false, "scale kernel chunk budget waits at FIFO head");
        }
        if (observation.hotspot()) {
            AGCRegionHotspotMap.INSTANCE.claimHotspotWork(observation.cellKey(), 1L, "chunk hotspot accounting");
        }
        this.ioHints.incrementAndGet();
        this.planned.incrementAndGet();
        return new Admission(true, "chunk intent planned; Paper completion order preserved");
    }

    public String statusLine() {
        return "AGCChunkPipelineKernel{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", players=" + this.perPlayerTickets.size()
            + ", planned=" + this.planned.get()
            + ", waits=" + this.waits.get()
            + ", ioHints=" + this.ioHints.get()
            + '}';
    }

    public record Admission(boolean admitted, String reason) {
    }
}
