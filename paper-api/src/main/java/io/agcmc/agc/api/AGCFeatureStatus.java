package io.agcmc.agc.api;

/** High-level state exposed to plugins without requiring NMS access. */
public enum AGCFeatureStatus {
    ENABLED,
    DISABLED,
    ROLLED_BACK,
    BLOCKED_BY_COMPATIBILITY,
    UNKNOWN
}
