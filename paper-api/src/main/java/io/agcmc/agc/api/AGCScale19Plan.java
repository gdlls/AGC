package io.agcmc.agc.api;

/**
 * Alpha19 plugin-facing plan for lossless logic/algorithm optimisation.
 * It exposes conservative sizing hints for packet shape graphs, chunk demand
 * forecasts, entity interest reduction, read-only phase batches and locality
 * scratch without changing Bukkit-visible ordering semantics.
 */
public record AGCScale19Plan(
    int expectedPlayers,
    double targetTps,
    int reservedCpuPercent,
    int readOnlyWorkers,
    int multicastGroups,
    int chunkForecastRadius,
    int chunkForecastHints,
    int entityInterestBands,
    int worldPhaseBatches,
    int pluginSemanticCacheEntries,
    long adaptiveLocalityBytes,
    boolean preservesPacketOrder,
    boolean preservesChunkFifo,
    boolean preservesEntityTicks,
    String advice
) {}
