package io.agcmc.agc.api.minigame;

import io.agcmc.agc.api.AGCPerformanceAdvice;
import org.jetbrains.annotations.NotNull;

/** API-level mini-game performance plan for arena plugins. */
public record AGCMinigamePerformancePlan(
    int expectedPlayers,
    int expectedEntities,
    int preloadRadius,
    int recommendedShardSize,
    boolean readOnlyPrewarm,
    boolean orderedReset,
    @NotNull AGCPerformanceAdvice advice
) {
}
