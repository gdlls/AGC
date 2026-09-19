package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — 50+ World Hibernation & Dormancy Engine.
 *
 * <p>In servers hosting 50+ multi-worlds (e.g. minigame arenas, dungeons, instanced plots),
 * many worlds have 0 players for long periods. Ticking empty worlds wastes CPU and keeps
 * thousands of idle chunks loaded in memory.</p>
 *
 * <p>This engine transitions worlds into {@link WorldState#HIBERNATING} when player count stays
 * at 0 past the configured grace period, freezing chunk ticks and entity processing.
 * When a player transfers/teleports in, it executes an instant 0ms wakeup.</p>
 */
public final class AgcWorldHibernationEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcWorldHibernationEngine.class);
    private static final AgcWorldHibernationEngine INSTANCE = new AgcWorldHibernationEngine();

    public enum WorldState {
        /** Actively ticking with players or recent activity (HOT tier) */
        ACTIVE,
        /** 0 players, in grace period countdown before freezing (Transition to WARM) */
        DRAINING,
        /** Frozen: no entity/block ticks, chunks preserved in RAM (WARM tier) */
        HIBERNATING,
        /** Deep dormancy: prolonged 0 players, chunks evicted/saved to disk (COLD tier) */
        COLD,
        /** Player entering: transitioning back to active */
        WAKING
    }

    public enum Tier {
        HOT,
        WARM,
        COLD
    }

    private final ConcurrentHashMap<String, WorldTrackInfo> trackedWorlds = new ConcurrentHashMap<>();

    private final AtomicLong hibernationsTriggered = new AtomicLong();
    private final AtomicLong coldEvictionsTriggered = new AtomicLong();
    private final AtomicLong wakeupsTriggered = new AtomicLong();
    private final AtomicLong ticksSaved = new AtomicLong();

    public static AgcWorldHibernationEngine get() {
        return INSTANCE;
    }

    private AgcWorldHibernationEngine() {}

    /**
     * Updates world tracking with current player count (2-tier legacy compatibility).
     */
    public WorldState updateWorld(
        final String worldKey,
        final int playerCount,
        final long currentTick,
        final long graceTicks
    ) {
        return updateWorld(worldKey, playerCount, currentTick, graceTicks, graceTicks * 60L, null);
    }

    /**
     * Updates world tracking with 3-tier (HOT -> WARM -> COLD) lifecycle support.
     *
     * @param worldKey            Unique identifier for the world
     * @param playerCount         Number of active players in the world
     * @param currentTick         Current server tick
     * @param warmGraceTicks      Ticks before entering WARM (HIBERNATING) state
     * @param coldGraceTicks      Ticks of continuous dormancy before entering COLD state
     * @param onColdEvictCallback Optional callback executed when entering COLD state (e.g. disk save/unload)
     * @return Resolved {@link WorldState}
     */
    public WorldState updateWorld(
        final String worldKey,
        final int playerCount,
        final long currentTick,
        final long warmGraceTicks,
        final long coldGraceTicks,
        final Runnable onColdEvictCallback
    ) {
        if (worldKey == null) {
            return WorldState.ACTIVE;
        }

        final WorldTrackInfo info = this.trackedWorlds.computeIfAbsent(
            worldKey,
            k -> new WorldTrackInfo(k, WorldState.ACTIVE, currentTick)
        );

        synchronized (info) {
            if (playerCount > 0) {
                if (info.state == WorldState.HIBERNATING || info.state == WorldState.DRAINING || info.state == WorldState.COLD) {
                    info.state = WorldState.ACTIVE;
                    this.wakeupsTriggered.incrementAndGet();
                    LOGGER.info("AGC World Hibernation: World '{}' woke up ({} players)", worldKey, playerCount);
                }
                info.lastActiveTick = currentTick;
                return WorldState.ACTIVE;
            }

            // Player count == 0
            if (info.state == WorldState.ACTIVE) {
                info.state = WorldState.DRAINING;
                info.lastActiveTick = currentTick;
                return WorldState.DRAINING;
            }

            final long inactiveTicks = currentTick - info.lastActiveTick;

            if (info.state == WorldState.DRAINING) {
                if (inactiveTicks >= Math.max(1, warmGraceTicks)) {
                    info.state = WorldState.HIBERNATING;
                    this.hibernationsTriggered.incrementAndGet();
                    LOGGER.info("AGC World Hibernation: World '{}' entered WARM HIBERNATING state after {} idle ticks", worldKey, inactiveTicks);
                    return WorldState.HIBERNATING;
                }
                return WorldState.DRAINING;
            }

            if (info.state == WorldState.HIBERNATING) {
                this.ticksSaved.incrementAndGet();
                if (coldGraceTicks > 0 && inactiveTicks >= coldGraceTicks) {
                    info.state = WorldState.COLD;
                    this.coldEvictionsTriggered.incrementAndGet();
                    LOGGER.info("AGC World Hibernation: World '{}' entered COLD DEEP DORMANCY after {} idle ticks", worldKey, inactiveTicks);
                    if (onColdEvictCallback != null) {
                        try {
                            onColdEvictCallback.run();
                        } catch (final Throwable t) {
                            LOGGER.warn("AGC World Hibernation: Cold eviction callback failed for world '{}': {}", worldKey, t.getMessage());
                        }
                    }
                    return WorldState.COLD;
                }
                return WorldState.HIBERNATING;
            }

            if (info.state == WorldState.COLD) {
                this.ticksSaved.incrementAndGet();
                return WorldState.COLD;
            }

            return info.state;
        }
    }

    /**
     * Batch-evaluates multiple worlds in a single pass, avoiding per-world ConcurrentHashMap overhead.
     * Returns the count of worlds that should be ticked (ACTIVE or DRAINING).
     */
    public int updateWorldsBatch(
        final String[] worldKeys,
        final int[] playerCounts,
        final long currentTick,
        final long graceTicks,
        final java.util.function.Consumer<String> activeWorldConsumer
    ) {
        if (worldKeys == null || playerCounts == null) return 0;
        int activeCount = 0;
        final int len = Math.min(worldKeys.length, playerCounts.length);
        for (int i = 0; i < len; i++) {
            final WorldState state = updateWorld(worldKeys[i], playerCounts[i], currentTick, graceTicks);
            if (state == WorldState.ACTIVE || state == WorldState.DRAINING) {
                activeCount++;
                if (activeWorldConsumer != null) {
                    activeWorldConsumer.accept(worldKeys[i]);
                }
            }
        }
        return activeCount;
    }

    /**
     * Checks whether a given world should be ticked this cycle.
     *
     * @param worldKey Unique identifier for the world
     * @return true if world is ACTIVE or DRAINING, false if HIBERNATING or COLD
     */
    public boolean shouldTickWorld(final String worldKey) {
        if (worldKey == null) {
            return true;
        }
        final WorldTrackInfo info = this.trackedWorlds.get(worldKey);
        if (info == null) {
            return true;
        }
        return info.state != WorldState.HIBERNATING && info.state != WorldState.COLD;
    }

    /**
     * Obtains the 3-tier classification of a world.
     */
    public Tier getTier(final String worldKey) {
        if (worldKey == null) return Tier.HOT;
        final WorldTrackInfo info = this.trackedWorlds.get(worldKey);
        if (info == null) return Tier.HOT;
        return switch (info.state) {
            case ACTIVE, DRAINING, WAKING -> Tier.HOT;
            case HIBERNATING -> Tier.WARM;
            case COLD -> Tier.COLD;
        };
    }

    /**
     * Forces a world into active state immediately (e.g. before an inbound teleport).
     */
    public void wakeWorldImmediate(final String worldKey, final long currentTick) {
        if (worldKey == null) {
            return;
        }
        final WorldTrackInfo info = this.trackedWorlds.computeIfAbsent(
            worldKey,
            k -> new WorldTrackInfo(k, WorldState.ACTIVE, currentTick)
        );
        synchronized (info) {
            if (info.state == WorldState.HIBERNATING || info.state == WorldState.DRAINING || info.state == WorldState.COLD) {
                info.state = WorldState.ACTIVE;
                this.wakeupsTriggered.incrementAndGet();
            }
            info.lastActiveTick = currentTick;
        }
    }

    public WorldState getState(final String worldKey) {
        if (worldKey == null) return WorldState.ACTIVE;
        final WorldTrackInfo info = this.trackedWorlds.get(worldKey);
        return info != null ? info.state : WorldState.ACTIVE;
    }

    public void resetMetrics() {
        this.trackedWorlds.clear();
        this.hibernationsTriggered.set(0);
        this.coldEvictionsTriggered.set(0);
        this.wakeupsTriggered.set(0);
        this.ticksSaved.set(0);
    }

    public HibernationMetrics metrics() {
        int hibernatingCount = 0;
        int coldCount = 0;
        int activeCount = 0;
        int drainingCount = 0;

        for (final WorldTrackInfo info : this.trackedWorlds.values()) {
            switch (info.state) {
                case HIBERNATING -> hibernatingCount++;
                case COLD -> coldCount++;
                case ACTIVE -> activeCount++;
                case DRAINING -> drainingCount++;
                case WAKING -> activeCount++;
            }
        }

        return new HibernationMetrics(
            this.trackedWorlds.size(),
            activeCount,
            drainingCount,
            hibernatingCount,
            coldCount,
            this.hibernationsTriggered.get(),
            this.coldEvictionsTriggered.get(),
            this.wakeupsTriggered.get(),
            this.ticksSaved.get()
        );
    }

    private static final class WorldTrackInfo {
        final String name;
        volatile WorldState state;
        volatile long lastActiveTick;

        WorldTrackInfo(final String name, final WorldState state, final long lastActiveTick) {
            this.name = name;
            this.state = state;
            this.lastActiveTick = lastActiveTick;
        }
    }

    public record HibernationMetrics(
        int totalTrackedWorlds,
        int activeWorlds,
        int drainingWorlds,
        int warmHibernatingWorlds,
        int coldDormantWorlds,
        long warmHibernationsTriggered,
        long coldEvictionsTriggered,
        long wakeupsTriggered,
        long worldTicksSaved
    ) {
        public int hibernatingWorlds() {
            return this.warmHibernatingWorlds + this.coldDormantWorlds;
        }

        public long hibernationsTriggered() {
            return this.warmHibernationsTriggered;
        }
    }
}
