package io.agcmc.agc.api;

import org.jetbrains.annotations.NotNull;

/** Alpha22 large-scale tick work compiler plan for semantic-preserving survival scale. */
public record AGCScale22Plan(
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int helperWorkers,
    int tickWorkUnits,
    int networkBackboneNodes,
    int chunkTerrainStripes,
    int entityObserverBands,
    int multiversePhaseWaves,
    int pluginCommitTickets,
    long scratchBytes,
    boolean preservesPacketOrder,
    boolean preservesChunkFifo,
    boolean preservesEntityTicks,
    boolean preservesPluginOrder,
    @NotNull String strategy
) {}
