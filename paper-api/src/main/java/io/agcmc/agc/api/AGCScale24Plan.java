package io.agcmc.agc.api;

import org.jetbrains.annotations.NotNull;

/** Alpha24 hot-path compiled plan cache for large survival-scale servers. */
public record AGCScale24Plan(
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int helperWorkers,
    int hotPathCredits,
    int planCacheEntries,
    int networkOrderVectorLanes,
    int chunkHorizonWidth,
    int entityObserverSets,
    int worldReadOnlyBatches,
    int pluginSemanticTickets,
    long localityBytes,
    boolean preservesPacketOrder,
    boolean preservesChunkFifo,
    boolean preservesEntityTicks,
    boolean preservesPluginOrder,
    @NotNull String strategy
) {}
