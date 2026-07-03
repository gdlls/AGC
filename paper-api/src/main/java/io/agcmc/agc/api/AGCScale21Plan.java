package io.agcmc.agc.api;

/**
 * Public alpha21 scale guidance for plugins targeting ordinary survival
 * servers with thousands of players while preserving Bukkit/Paper semantics.
 */
public record AGCScale21Plan(
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int readOnlyWorkers,
    int playerClusterSize,
    int chunkStormSliceWidth,
    int entityDensityCandidateBudget,
    int networkBroadcastCohorts,
    int multiversePrepareLanes,
    int pluginCommitTicketsPerTick,
    long localityScratchBytes,
    boolean preservesPacketOrder,
    boolean preservesChunkFifo,
    boolean preservesEntityTicks,
    boolean preservesPluginEvents,
    String recommendation
) {}
