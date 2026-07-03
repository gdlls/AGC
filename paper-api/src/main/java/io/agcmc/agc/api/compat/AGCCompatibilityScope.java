package io.agcmc.agc.api.compat;

/** Scope in which a compatibility contract applies. */
public enum AGCCompatibilityScope {
    GLOBAL,
    WORLD,
    ARENA,
    PLAYER,
    PACKET,
    CHUNK,
    ENTITY
}
