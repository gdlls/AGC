package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local player cohort table for hotspot survival servers. Cohorts are only
 * planning labels used to share read-only fanout and visibility calculations;
 * they do not hide players, shrink view distance or alter tracking semantics.
 */
public final class AGCPlayerCohortTable {
    public static final AGCPlayerCohortTable INSTANCE = new AGCPlayerCohortTable();

    private final ConcurrentHashMap<Long, Cohort> cohorts = new ConcurrentHashMap<>();
    private final AtomicLong playersRecorded = new AtomicLong();
    private final AtomicLong sharedPlans = new AtomicLong();
    private final AtomicLong orderedPlans = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int cellSizeBlocks = 64;
    private volatile int targetPlayersPerCohort = 32;
    private volatile int maxCohorts = 524_288;
    private volatile long tickSequence;

    private AGCPlayerCohortTable() {
    }

    public void configure(final boolean enabled, final int cellSizeBlocks, final int targetPlayersPerCohort, final int maxCohorts) {
        this.enabled = enabled;
        this.cellSizeBlocks = Math.max(8, cellSizeBlocks);
        this.targetPlayersPerCohort = Math.max(1, targetPlayersPerCohort);
        this.maxCohorts = Math.max(1, maxCohorts);
    }

    public void beginTick(final long sequence) {
        if (sequence != this.tickSequence) {
            this.tickSequence = sequence;
            this.cohorts.clear();
        }
    }

    public CohortDecision record(final UUID playerId, final String worldKey, final int blockX, final int blockZ) {
        if (!this.enabled || playerId == null) {
            this.orderedPlans.incrementAndGet();
            return new CohortDecision(false, 0L, 0, "cohort disabled or anonymous player");
        }
        this.playersRecorded.incrementAndGet();
        if (this.cohorts.size() >= this.maxCohorts) {
            this.orderedPlans.incrementAndGet();
            return new CohortDecision(false, 0L, 0, "cohort table full; keep per-player ordered planning");
        }
        final long key = key(worldKey, Math.floorDiv(blockX, this.cellSizeBlocks), Math.floorDiv(blockZ, this.cellSizeBlocks));
        final Cohort cohort = this.cohorts.computeIfAbsent(key, ignored -> new Cohort(key));
        final int size = cohort.players.incrementAndGet();
        final long cost = Math.max(1L, size / Math.max(1, this.targetPlayersPerCohort));
        final AGCScaleControlPlane.Admission admission = AGCScaleControlPlane.INSTANCE.claim(AGCScaleControlPlane.Lane.PLAYER_COHORT, cost, "cohort " + key);
        if (!admission.admitted()) {
            this.orderedPlans.incrementAndGet();
            return new CohortDecision(false, key, size, admission.reason());
        }
        this.sharedPlans.incrementAndGet();
        return new CohortDecision(size >= 2, key, size, "cohort read-only plan shared; gameplay visibility unchanged");
    }

    public String statusLine() {
        return "AGCPlayerCohortTable{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", cohorts=" + this.cohorts.size()
            + ", playersRecorded=" + this.playersRecorded.get()
            + ", sharedPlans=" + this.sharedPlans.get()
            + ", orderedPlans=" + this.orderedPlans.get()
            + '}';
    }

    private static long key(final String worldKey, final int cellX, final int cellZ) {
        long hash = 1125899906842597L;
        hash = 31L * hash + (worldKey == null ? 0 : worldKey.hashCode());
        hash = 31L * hash + cellX;
        hash = 31L * hash + cellZ;
        return hash;
    }

    private static final class Cohort {
        private final long key;
        private final AtomicLong age = new AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger players = new java.util.concurrent.atomic.AtomicInteger();

        private Cohort(final long key) {
            this.key = key;
        }
    }

    public record CohortDecision(boolean shared, long cohortKey, int cohortSize, String reason) {
    }
}
