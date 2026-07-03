package io.agcmc.agc.api;

/**
 * Alpha18 plugin-facing plan for integrated tick planning.
 * It describes how large servers should split read-only prepare, lossless
 * fanout planning, FIFO chunk intents and ordered Bukkit-visible commits.
 */
public record AGCScale18Plan(
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int readOnlyWorkers,
    int unifiedPrepareWaves,
    int orderedCommitBarriers,
    int networkCohortSize,
    int chunkRouteRadius,
    int entityDeltaBands,
    long tickLocalScratchBytes,
    boolean usesUnifiedTickPlan,
    boolean preservesBukkitOrder,
    boolean preservesChunkFifo,
    String advice
) {}
