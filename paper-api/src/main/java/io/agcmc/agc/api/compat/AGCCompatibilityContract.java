package io.agcmc.agc.api.compat;

/**
 * Compatibility contracts a plugin can declare to AGC.
 * <p>
 * These contracts never grant AGC permission to change Bukkit event order or run
 * Bukkit mutations off the primary thread. They only let AGC safely move
 * read-only preparation, packet grouping and arena-local bookkeeping into the
 * correct no-invasion lane without guessing from plugin names.
 */
public enum AGCCompatibilityContract {
    /** Plugin never synchronously mutates a different world while handling a world event. */
    WORLD_LOCAL_EVENTS,
    /** Plugin tolerates arena-local read-only preparation before ordered Bukkit commits. */
    ARENA_READ_ONLY_PREPARE,
    /** Plugin does not depend on cosmetic packet flushes occurring before the next tick deadline. */
    DEADLINE_ORDERED_COSMETIC_PACKETS,
    /** Plugin keeps chunk load/generate requests FIFO per player/arena. */
    FIFO_CHUNK_INTENTS,
    /** Plugin is safe with AGC hiding non-arena players through ordinary Bukkit visibility APIs. */
    ARENA_VISIBILITY_ISOLATION,
    /** Plugin only reads entity tracker snapshots produced by AGC; it does not mutate entity tick order. */
    READ_ONLY_ENTITY_SNAPSHOTS,
    /** Plugin declares that all unsafe work will be scheduled through AGC.scheduler(). */
    PRIMARY_THREAD_COMMIT_DISCIPLINE
}
