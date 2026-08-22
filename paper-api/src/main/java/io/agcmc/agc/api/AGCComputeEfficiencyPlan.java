package io.agcmc.agc.api;

/**
 * Plugin-facing compute guidance for large survival networks.
 * <p>
 * The plan intentionally separates read-only work from Bukkit-visible commits:
 * plugins may use the read-only batch sizes for pathfinding pre-analysis,
 * scoreboard/cosmetic fanout preparation, arena/survival interest grouping and
 * database metadata lookups, but all Bukkit mutations should still be committed
 * through the ordered primary-thread lane.
 */
public record AGCComputeEfficiencyPlan(
    int expectedPlayers,
    int targetTps,
    int suggestedReadOnlyWorkers,
    int readOnlyBatchSize,
    int fanoutBatchSize,
    int chunkIntentBatchSize,
    int entityVisibilityBatchSize,
    int orderedCommitBatchSize,
    int reservedCpuPercent,
    @org.jetbrains.annotations.NotNull String policy
) {
    public AGCComputeEfficiencyPlan {
        expectedPlayers = Math.max(1, expectedPlayers);
        targetTps = Math.max(1, targetTps);
        suggestedReadOnlyWorkers = Math.max(1, suggestedReadOnlyWorkers);
        readOnlyBatchSize = Math.max(1, readOnlyBatchSize);
        fanoutBatchSize = Math.max(1, fanoutBatchSize);
        chunkIntentBatchSize = Math.max(1, chunkIntentBatchSize);
        entityVisibilityBatchSize = Math.max(1, entityVisibilityBatchSize);
        orderedCommitBatchSize = Math.max(1, orderedCommitBatchSize);
        reservedCpuPercent = Math.max(0, Math.min(90, reservedCpuPercent));
        policy = policy == null ? "read-only prepare plus ordered commit" : policy;
    }
}
