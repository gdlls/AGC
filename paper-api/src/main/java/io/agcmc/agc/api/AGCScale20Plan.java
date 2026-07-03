package io.agcmc.agc.api;

/**
 * Public alpha20 scale plan for plugins targeting very large survival servers.
 * Values are advisory only: plugins should use read-only prepare and ordered
 * commit patterns instead of moving Bukkit-visible mutations off-thread.
 */
public record AGCScale20Plan(
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int helperWorkers,
    int networkBackboneSpines,
    int networkBackboneLeaves,
    int chunkWorksetSlices,
    int chunkLookaheadRadius,
    int entityWitnessBands,
    int worldPrepareSlots,
    int pluginSemanticCacheBudget,
    long helperLocalityBytes,
    boolean packetDropFree,
    boolean chunkFifoPreserved,
    boolean entityTickPreserved,
    boolean pluginOrderPreserved,
    String strategy
) {}
