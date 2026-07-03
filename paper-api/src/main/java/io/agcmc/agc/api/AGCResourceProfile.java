package io.agcmc.agc.api;

import org.jetbrains.annotations.NotNull;

/** API-only guidance for compute-resource efficient plugin work. */
public record AGCResourceProfile(
    int targetPlayers,
    double targetTps,
    int recommendedReadOnlyBatchSize,
    int recommendedCommitBatchSize,
    boolean useReadOnlyPrepare,
    boolean keepVisibleCommitsOrdered,
    @NotNull String reason
) {
}
