package io.agcmc.agc.api;

import org.jetbrains.annotations.NotNull;

/** Stable API-only decision object for plugin performance helpers. */
public record AGCPerformanceAdvice(
    boolean admitted,
    @NotNull Lane lane,
    int recommendedBatchSize,
    @NotNull String reason
) {
    public enum Lane {
        RUN_NOW_ORDERED,
        READ_ONLY_PREPARE,
        DEADLINE_BATCH,
        FIFO_WAIT,
        ORDERED_COMMIT
    }
}
