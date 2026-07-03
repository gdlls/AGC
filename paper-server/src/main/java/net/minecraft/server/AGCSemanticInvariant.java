package net.minecraft.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime ledger for AGC's non-invasive optimisation contract.
 * <p>
 * AGC alpha8 deliberately separates optimisation work into two categories:
 * immutable/read-only preparation may run early or in parallel, while every
 * mutation, packet-order decision, Bukkit event, world commit and plugin-facing
 * callback remains in the same deterministic order the main thread observes.
 * This class is a small, allocation-light ledger used by each subsystem to
 * prove that it chose a semantic-preserving algorithm rather than changing the
 * gameplay contract under load.
 */
public final class AGCSemanticInvariant {
    public static final AGCSemanticInvariant INSTANCE = new AGCSemanticInvariant();

    public enum Domain {
        NETWORK,
        CHUNK,
        WORLD_TICK,
        ENTITY,
        PLUGIN,
        PLAYER,
        MINIGAME
    }

    public enum Decision {
        READ_ONLY_PARALLEL_PREPARE,
        MAIN_THREAD_ORDERED_COMMIT,
        DEADLINE_PRESERVED_BATCH,
        CONFLICT_SERIALISED,
        IMMEDIATE_ORDER_PRESERVED,
        ROLLBACK_DENIED
    }

    private final EnumMap<Domain, AtomicLong> readOnlyPrepare = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> orderedCommit = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> deadlineBatch = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> conflictSerialised = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> immediatePreserved = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> rollbackDenied = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> lastJournalNanos = new EnumMap<>(Domain.class);

    private volatile boolean enabled = true;
    private volatile long tickSequence;
    private volatile long lastCommitSequence;
    private volatile int lastWorldPlanWaves;
    private volatile int lastWorldPlanParallelGroups;
    private volatile String lastWorldPlanMode = "not-sampled";

    private AGCSemanticInvariant() {
        for (final Domain domain : Domain.values()) {
            this.readOnlyPrepare.put(domain, new AtomicLong());
            this.orderedCommit.put(domain, new AtomicLong());
            this.deadlineBatch.put(domain, new AtomicLong());
            this.conflictSerialised.put(domain, new AtomicLong());
            this.immediatePreserved.put(domain, new AtomicLong());
            this.rollbackDenied.put(domain, new AtomicLong());
            this.lastJournalNanos.put(domain, new AtomicLong());
        }
    }

    public void configure(final boolean enabled) {
        this.enabled = enabled;
    }

    public long nextTickSequence() {
        return this.tickSequence + 1L;
    }

    public void beginTick(final long tickSequence) {
        this.tickSequence = Math.max(0L, tickSequence);
    }

    public void record(final Domain domain, final Decision decision, final String reason) {
        if (!this.enabled) {
            return;
        }
        final Domain normalized = domain == null ? Domain.PLUGIN : domain;
        final Decision normalizedDecision = decision == null ? Decision.IMMEDIATE_ORDER_PRESERVED : decision;
        switch (normalizedDecision) {
            case READ_ONLY_PARALLEL_PREPARE -> this.readOnlyPrepare.get(normalized).incrementAndGet();
            case MAIN_THREAD_ORDERED_COMMIT -> {
                this.orderedCommit.get(normalized).incrementAndGet();
                this.lastCommitSequence = this.tickSequence;
            }
            case DEADLINE_PRESERVED_BATCH -> this.deadlineBatch.get(normalized).incrementAndGet();
            case CONFLICT_SERIALISED -> this.conflictSerialised.get(normalized).incrementAndGet();
            case IMMEDIATE_ORDER_PRESERVED -> this.immediatePreserved.get(normalized).incrementAndGet();
            case ROLLBACK_DENIED -> this.rollbackDenied.get(normalized).incrementAndGet();
        }
        if (normalizedDecision == Decision.CONFLICT_SERIALISED || normalizedDecision == Decision.ROLLBACK_DENIED) {
            this.journal(normalized, normalizedDecision, reason);
        }
    }

    public void recordWorldPlan(final String mode, final int waves, final int parallelGroups) {
        this.lastWorldPlanMode = mode == null || mode.isBlank() ? "unspecified" : mode;
        this.lastWorldPlanWaves = Math.max(0, waves);
        this.lastWorldPlanParallelGroups = Math.max(0, parallelGroups);
    }

    private void journal(final Domain domain, final Decision decision, final String reason) {
        final long now = System.nanoTime();
        final AtomicLong slot = this.lastJournalNanos.get(domain);
        final long previous = slot.get();
        if (now - previous < java.util.concurrent.TimeUnit.SECONDS.toNanos(5L)) {
            return;
        }
        if (slot.compareAndSet(previous, now)) {
            AGCStabilityJournal.INSTANCE.record(
                "semantic-invariant",
                domain + " -> " + decision + ": " + (reason == null || reason.isBlank() ? "unspecified" : reason)
            );
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.tickSequence,
            this.lastCommitSequence,
            this.lastWorldPlanMode,
            this.lastWorldPlanWaves,
            this.lastWorldPlanParallelGroups,
            copy(this.readOnlyPrepare),
            copy(this.orderedCommit),
            copy(this.deadlineBatch),
            copy(this.conflictSerialised),
            copy(this.immediatePreserved),
            copy(this.rollbackDenied)
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCSemanticInvariant{enabled=" + this.enabled
            + ", tick=" + snapshot.tickSequence()
            + ", lastCommit=" + snapshot.lastCommitSequence()
            + ", worldPlan=" + snapshot.lastWorldPlanMode()
            + ", waves=" + snapshot.lastWorldPlanWaves()
            + ", parallelGroups=" + snapshot.lastWorldPlanParallelGroups()
            + ", readOnlyPrepare=" + snapshot.readOnlyPrepare()
            + ", orderedCommit=" + snapshot.orderedCommit()
            + ", deadlineBatch=" + snapshot.deadlineBatch()
            + ", conflictSerialised=" + snapshot.conflictSerialised()
            + ", immediateOrderPreserved=" + snapshot.immediateOrderPreserved()
            + ", rollbackDenied=" + snapshot.rollbackDenied()
            + '}';
    }

    private static Map<Domain, Long> copy(final EnumMap<Domain, AtomicLong> source) {
        final EnumMap<Domain, Long> copy = new EnumMap<>(Domain.class);
        for (final Map.Entry<Domain, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(copy);
    }

    public record Snapshot(
        long tickSequence,
        long lastCommitSequence,
        String lastWorldPlanMode,
        int lastWorldPlanWaves,
        int lastWorldPlanParallelGroups,
        Map<Domain, Long> readOnlyPrepare,
        Map<Domain, Long> orderedCommit,
        Map<Domain, Long> deadlineBatch,
        Map<Domain, Long> conflictSerialised,
        Map<Domain, Long> immediateOrderPreserved,
        Map<Domain, Long> rollbackDenied
    ) {
    }
}
