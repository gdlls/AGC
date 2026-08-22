package io.agcmc.agc.api;

/**
 * Public alpha14 guidance for large ordinary survival servers.
 * <p>
 * This is intentionally a plan, not a promise: it tells plugins how AGC expects
 * heavy work to be split so thousands-player servers can use CPU efficiently
 * without breaking Bukkit/Paper semantics.
 */
public record AGCWildScalePlan(
    int expectedPlayers,
    double targetTps,
    int suggestedReadOnlyWorkers,
    int networkFanoutBatch,
    int chunkIntentBatch,
    int entityCandidateBatch,
    int regionHotspotCellSize,
    int reservedCpuPercent,
    boolean preservePluginEventOrder,
    boolean preserveChunkFifo,
    boolean preserveEntityTicks,
    @org.jetbrains.annotations.NotNull String policy
) {
    public AGCWildScalePlan {
        expectedPlayers = Math.max(1, expectedPlayers);
        targetTps = Math.max(1.0D, targetTps);
        suggestedReadOnlyWorkers = Math.max(1, suggestedReadOnlyWorkers);
        networkFanoutBatch = Math.max(1, networkFanoutBatch);
        chunkIntentBatch = Math.max(1, chunkIntentBatch);
        entityCandidateBatch = Math.max(1, entityCandidateBatch);
        regionHotspotCellSize = Math.max(16, regionHotspotCellSize);
        reservedCpuPercent = Math.max(0, Math.min(90, reservedCpuPercent));
        policy = policy == null ? "read-only prepare plus ordered commit" : policy;
    }
}
