package io.agcmc.agc.api;

import org.jetbrains.annotations.NotNull;

/** Plugin-facing plan for high-density normal survival features. */
public record AGCSurvivalScalePlan(
    int expectedPlayers,
    int activeWorlds,
    int estimatedLoadedChunks,
    int estimatedEntities,
    int recommendedPlanningShards,
    int recommendedInterestCellSize,
    int recommendedPlayersPerCell,
    @NotNull AGCPerformanceAdvice.Lane networkLane,
    @NotNull AGCPerformanceAdvice.Lane chunkLane,
    @NotNull AGCPerformanceAdvice.Lane entityLane,
    @NotNull String strategy
) {
}
