package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Safe chunk backpressure hook.
 * <p>
 * AGC does not cancel, skip or corrupt chunk data. Send budgeting coalesces
 * socket flushes inside the network deadline; load/generate budgeting uses a
 * per-player fair admission cursor but never reorders or deletes queued chunks.
 * This keeps redstone/farms/block entity timing tied to the existing chunk state
 * machine instead of inventing async chunk semantics.
 */
public final class AGCChunkAdmission {
    public static final AGCChunkAdmission INSTANCE = new AGCChunkAdmission();

    private final AtomicLong requestedChunkSends = new AtomicLong();
    private final AtomicLong immediateFlushChunkSends = new AtomicLong();
    private final AtomicLong deferredFlushChunkSends = new AtomicLong();
    private final AtomicLong requestedLoads = new AtomicLong();
    private final AtomicLong admittedLoads = new AtomicLong();
    private final AtomicLong throttledLoads = new AtomicLong();
    private final AtomicLong requestedGenerates = new AtomicLong();
    private final AtomicLong admittedGenerates = new AtomicLong();
    private final AtomicLong throttledGenerates = new AtomicLong();

    private AGCChunkAdmission() {
    }

    public boolean shouldDeferChunkFlush(final @Nullable ServerPlayer player) {
        this.requestedChunkSends.incrementAndGet();
        if (player == null
            || !AGCChunkBudget.INSTANCE.isEnabled()
            || !AGCPerformanceGovernor.INSTANCE.isFeatureAllowed(AGCPerformanceGovernor.Feature.CHUNK_QUEUE_BUDGETING)) {
            this.immediateFlushChunkSends.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.CHUNK, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, "chunk send priority");
            return false;
        }
        final AGCOptimizationEnvelope.Outcome envelope = AGCOptimizationEnvelope.INSTANCE.admitChunkOperation(
            AGCOptimizationEnvelope.Area.CHUNK_SEND,
            "chunk packet flush coalescing"
        );
        if (!AGCOptimizationEnvelope.INSTANCE.isAggressive(envelope)) {
            this.immediateFlushChunkSends.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.CHUNK, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, "chunk send order preserved");
            return false;
        }
        final AGCTickBudgetArbiter.Admission tickBudget = AGCTickBudgetArbiter.INSTANCE.claim(
            AGCTickBudgetArbiter.Category.CHUNK_SEND,
            1L,
            false
        );
        final boolean admitted = tickBudget.admitted()
            && AGCChunkFairQueue.INSTANCE.admit(player.getUUID(), AGCChunkBudget.Operation.SEND).admitted()
            && AGCChunkBudget.INSTANCE.admit(player.getUUID(), AGCChunkBudget.Operation.SEND);
        if (admitted) {
            this.immediateFlushChunkSends.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.CHUNK, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, "chunk send budget admitted");
            return false;
        }
        this.deferredFlushChunkSends.incrementAndGet();
        AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.CHUNK, AGCSemanticInvariant.Decision.DEADLINE_PRESERVED_BATCH, "chunk send flush coalesced");
        return true;
    }

    /**
     * Admission for starting new chunk loads/generations. False means "try next
     * tick", not "drop this chunk".
     */
    public boolean admitOperation(final @Nullable ServerPlayer player, final AGCChunkBudget.Operation operation) {
        if (operation == AGCChunkBudget.Operation.GENERATE) {
            this.requestedGenerates.incrementAndGet();
        } else if (operation == AGCChunkBudget.Operation.LOAD) {
            this.requestedLoads.incrementAndGet();
        }
        if (player == null
            || operation == null
            || operation == AGCChunkBudget.Operation.SEND
            || !AGCChunkBudget.INSTANCE.isEnabled()
            || !AGCPerformanceGovernor.INSTANCE.isFeatureAllowed(AGCPerformanceGovernor.Feature.CHUNK_QUEUE_BUDGETING)) {
            this.markAdmitted(operation);
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.CHUNK, AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT, "chunk operation primary path " + operation);
            return true;
        }
        final AGCOptimizationEnvelope.Area area = operation == AGCChunkBudget.Operation.GENERATE
            ? AGCOptimizationEnvelope.Area.CHUNK_GENERATION
            : AGCOptimizationEnvelope.Area.CHUNK_LOAD;
        final AGCOptimizationEnvelope.Outcome envelope = AGCOptimizationEnvelope.INSTANCE.admitChunkOperation(area, "chunk " + operation + " queue admission");
        if (!AGCOptimizationEnvelope.INSTANCE.isAggressive(envelope)) {
            this.markAdmitted(operation);
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.CHUNK, AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT, "chunk operation ordered commit " + operation);
            return true;
        }
        final AGCPlayerIntentScheduler.IntentType intentType = switch (operation) {
            case LOAD -> AGCPlayerIntentScheduler.IntentType.CHUNK_LOAD;
            case GENERATE -> AGCPlayerIntentScheduler.IntentType.CHUNK_GENERATE;
            case SEND -> AGCPlayerIntentScheduler.IntentType.CHUNK_SEND;
        };
        final AGCPlayerIntentScheduler.Admission playerIntent = AGCPlayerIntentScheduler.INSTANCE.admit(player.getUUID(), intentType, 1);
        final AGCNoInvasionOptimizer.Decision decision = AGCNoInvasionOptimizer.INSTANCE.admitChunkQueue(player.getUUID(), operation);
        final boolean admitted = playerIntent.admitted()
            && decision == AGCNoInvasionOptimizer.Decision.ORDERED_COMMIT
            && AGCChunkBudget.INSTANCE.admit(player.getUUID(), operation);
        if (admitted) {
            this.markAdmitted(operation);
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.CHUNK, AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT, "chunk operation fifo admitted " + operation);
        } else {
            this.markThrottled(operation);
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.CHUNK, AGCSemanticInvariant.Decision.CONFLICT_SERIALISED, "chunk operation stays at fifo head until fair token " + operation);
        }
        return admitted;
    }

    private void markAdmitted(final AGCChunkBudget.Operation operation) {
        if (operation == AGCChunkBudget.Operation.GENERATE) {
            this.admittedGenerates.incrementAndGet();
        } else if (operation == AGCChunkBudget.Operation.LOAD) {
            this.admittedLoads.incrementAndGet();
        }
    }

    private void markThrottled(final AGCChunkBudget.Operation operation) {
        if (operation == AGCChunkBudget.Operation.GENERATE) {
            this.throttledGenerates.incrementAndGet();
        } else if (operation == AGCChunkBudget.Operation.LOAD) {
            this.throttledLoads.incrementAndGet();
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.requestedChunkSends.get(),
            this.immediateFlushChunkSends.get(),
            this.deferredFlushChunkSends.get(),
            this.requestedLoads.get(),
            this.admittedLoads.get(),
            this.throttledLoads.get(),
            this.requestedGenerates.get(),
            this.admittedGenerates.get(),
            this.throttledGenerates.get(),
            AGCChunkBudget.INSTANCE.snapshot()
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCChunkAdmission{requestedChunkSends=" + snapshot.requestedChunkSends()
            + ", immediateFlush=" + snapshot.immediateFlushChunkSends()
            + ", deferredFlush=" + snapshot.deferredFlushChunkSends()
            + ", loads=" + snapshot.admittedLoads() + '/' + snapshot.requestedLoads()
            + ", throttledLoads=" + snapshot.throttledLoads()
            + ", generates=" + snapshot.admittedGenerates() + '/' + snapshot.requestedGenerates()
            + ", throttledGenerates=" + snapshot.throttledGenerates()
            + ", chunkBudget=" + snapshot.chunkBudget()
            + ", fairQueue=" + AGCChunkFairQueue.INSTANCE.statusLine()
            + '}';
    }

    public record Snapshot(
        long requestedChunkSends,
        long immediateFlushChunkSends,
        long deferredFlushChunkSends,
        long requestedLoads,
        long admittedLoads,
        long throttledLoads,
        long requestedGenerates,
        long admittedGenerates,
        long throttledGenerates,
        AGCChunkBudget.Snapshot chunkBudget
    ) {
    }
}
