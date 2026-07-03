package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only entity tracker preselector for dense survival. It never skips entity
 * ticks, AI, damage, interactions or plugin events; it only budgets immutable
 * candidate-set preparation.
 */
public final class AGCEntityTrackerKernel {
    public static final AGCEntityTrackerKernel INSTANCE = new AGCEntityTrackerKernel();

    private final ConcurrentHashMap<UUID, AtomicLong> perPlayerBudget = new ConcurrentHashMap<>();
    private final AtomicLong prepared = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private final AtomicLong sharedBands = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile long perPlayerQuantum = 4096L;
    private volatile long tickSequence;

    private AGCEntityTrackerKernel() {
    }

    public void configure(final boolean enabled, final long perPlayerQuantum) {
        this.enabled = enabled;
        this.perPlayerQuantum = Math.max(1L, perPlayerQuantum);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        for (final AtomicLong budget : this.perPlayerBudget.values()) {
            budget.set(Math.min(this.perPlayerQuantum * 4L, budget.get() + this.perPlayerQuantum));
        }
    }

    public Admission prepare(final UUID playerId, final int estimatedEntities, final String reason) {
        if (!this.enabled || playerId == null || estimatedEntities <= 0) {
            this.ordered.incrementAndGet();
            return new Admission(false, "ordered tracker path");
        }
        final long cost = Math.max(1L, estimatedEntities / 16L);
        final AtomicLong budget = this.perPlayerBudget.computeIfAbsent(playerId, ignored -> new AtomicLong(this.perPlayerQuantum));
        while (true) {
            final long current = budget.get();
            if (current < cost) {
                this.ordered.incrementAndGet();
                return new Admission(false, "tracker candidate budget waits without tick skip");
            }
            if (budget.compareAndSet(current, current - cost)) {
                break;
            }
        }
        final AGCScaleKernel.Admission scale = AGCScaleKernel.INSTANCE.claim(AGCScaleKernel.Axis.ENTITY_TRACKER, cost, reason);
        if (!scale.admitted()) {
            this.ordered.incrementAndGet();
            return new Admission(false, scale.reason());
        }
        final int distanceBand = Math.min(16, Math.max(0, estimatedEntities / 128));
        if (distanceBand > 0) {
            this.sharedBands.incrementAndGet();
        }
        this.prepared.incrementAndGet();
        return new Admission(true, "read-only tracker candidates distanceBand=" + distanceBand);
    }

    public String statusLine() {
        return "AGCEntityTrackerKernel{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", players=" + this.perPlayerBudget.size()
            + ", prepared=" + this.prepared.get()
            + ", ordered=" + this.ordered.get()
            + ", sharedBands=" + this.sharedBands.get()
            + '}';
    }

    public record Admission(boolean prepared, String reason) {
    }
}
