package io.agcmc.agc.api;

/**
 * Public high-scale guidance for AGC alpha15. This is intentionally advisory:
 * plugins should use it to structure heavy work as read-only prepare, packet
 * shape reuse, FIFO chunk intents and ordered commits without depending on NMS.
 */
public record AGCScale15Plan(
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int readOnlyWorkers,
    int packetShapeBudget,
    int playerCohortSizeBlocks,
    int chunkIntentRegionSizeChunks,
    int entityBandCandidateLimit,
    int pluginTranslationBudget,
    long scratchBytesPerTick,
    boolean packetDropsAllowed,
    boolean entityTickSkippingAllowed,
    boolean offThreadBukkitMutationAllowed,
    String recommendation
) {
}
