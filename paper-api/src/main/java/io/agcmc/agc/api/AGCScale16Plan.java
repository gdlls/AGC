package io.agcmc.agc.api;

import org.jetbrains.annotations.NotNull;

/** Algorithmic high-scale plan for AGC alpha16. */
public record AGCScale16Plan(
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int readOnlyWorkers,
    int motionHorizonTicks,
    int chunkLookaheadRadius,
    int entitySpatialBandCap,
    int packetShapeBudget,
    boolean useWorldLogicDag,
    boolean usePluginSemanticModel,
    @NotNull String strategy
) {
}
