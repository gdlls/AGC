package io.agcmc.agc.api;

import org.jetbrains.annotations.NotNull;

/** Alpha23 compiled dispatch and stable-diff plan for semantic-preserving scale. */
public record AGCScale23Plan(
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int helperWorkers,
    int dispatchSlots,
    int networkDiffVectors,
    int chunkFrontierWidth,
    int entityVisibilityBands,
    int worldReadOnlyWaves,
    int pluginCoalescedTickets,
    long scratchBytes,
    boolean preservesPacketOrder,
    boolean preservesChunkFifo,
    boolean preservesEntityTicks,
    boolean preservesPluginOrder,
    @NotNull String strategy
) {}
