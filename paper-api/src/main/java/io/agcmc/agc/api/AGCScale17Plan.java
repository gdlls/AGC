package io.agcmc.agc.api;

/**
 * Public advisory plan for AGC alpha17 predictive algorithms.
 * <p>
 * This is API guidance only; it does not grant plugins permission to mutate
 * Bukkit state off the primary thread. Use it to split work into read-only
 * prediction/cohort/graph planning followed by ordered commit.
 */
public record AGCScale17Plan(
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int readOnlyWorkers,
    int chunkPredictiveRadius,
    int chunkPredictiveHints,
    int entityGraphCandidates,
    int networkRecipientsPerCohort,
    int worldPrepareWaves,
    boolean pluginContractVerifier,
    boolean tickLocalCacheWindow,
    String guidance
) {
}
