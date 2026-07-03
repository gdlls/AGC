package io.agcmc.agc.api;

/** High-level performance goals plugins can use to describe non-visible work. */
public enum AGCPerformanceGoal {
    LOW_LATENCY_COMBAT,
    DENSE_ARENA,
    MASS_CHUNK_PREWARM,
    ENTITY_VISIBILITY_PREPARE,
    COSMETIC_FANOUT,
    ORDERED_PLUGIN_COMMIT
}
