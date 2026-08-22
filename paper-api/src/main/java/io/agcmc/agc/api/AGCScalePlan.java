package io.agcmc.agc.api;

import org.jetbrains.annotations.NotNull;

/**
 * Unified high-scale performance and hardware resource plan for AGC.
 *
 * <p>Provides advisory sizing metrics for worker thread counts, memory locality windows,
 * chunk pipeline horizons, entity observation limits, and thread reservations to scale
 * Paper servers safely without invasive off-thread world mutation.</p>
 */
public record AGCScalePlan(
    int version,
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int helperWorkers,
    int workUnitsOrBatch,
    int networkBudgetOrLanes,
    int chunkRadiusOrHorizon,
    int entityCandidateLimit,
    long localityBytes,
    boolean preservesPacketOrder,
    boolean preservesChunkFifo,
    boolean preservesEntityTicks,
    boolean preservesPluginOrder,
    @NotNull String strategy
) {
    public AGCScalePlan {
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        if (expectedPlayers < 1) {
            throw new IllegalArgumentException("expectedPlayers must be >= 1");
        }
        if (targetTps <= 0.0) {
            throw new IllegalArgumentException("targetTps must be > 0.0");
        }
        if (strategy == null) {
            throw new NullPointerException("strategy");
        }
    }
}
