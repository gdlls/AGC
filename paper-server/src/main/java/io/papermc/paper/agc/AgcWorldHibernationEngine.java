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
        /** Actively ticking with players or recent activity */
        ACTIVE,
        /** 0 players, in grace period countdown before freezing */
        DRAINING,
        /** Frozen: no entity/block ticks, memory preserved */
        HIBERNATING,
        /** Player entering: transitioning back to active */
        WAKING
    }

    private final ConcurrentHashMap<String, WorldTrackInfo> trackedWorlds = new ConcurrentHashMap<>();

    // Telemetry & metrics
    private final AtomicLong hibernationsTriggered = new AtomicLong();
    private final AtomicLong wakeupsTriggered = new AtomicLong();
    private final AtomicLong ticksSaved = new AtomicLong();

    public static AgcWorldHibernationEngine get() {
        return INSTANCE;
    }

    private AgcWorldHibernationEngine() {}

    /**
     * Updates world tracking with current player count.
     *
     * @param worldKey    Unique identifier for the world
     * @param playerCount Number of active players in the world
     * @param currentTick Current server tick
     * @param graceTicks  Number of empty ticks before entering hibernation
     * @return Resolved {@link WorldState}
     */
    public WorldState updateWorld(
        final String worldKey,
        final int playerCount,
        final long currentTick,
        final long graceTicks
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
                if (info.state == WorldState.HIBERNATING || info.state == WorldState.DRAINING) {
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

            if (info.state == WorldState.DRAINING) {
                final long inactiveTicks = currentTick - info.lastActiveTick;
                if (inactiveTicks >= Math.max(1, graceTicks)) {
                    info.state = WorldState.HIBERNATING;
                    this.hibernationsTriggered.incrementAndGet();
                    LOGGER.info("AGC World Hibernation: World '{}' entered HIBERNATING state after {} idle ticks", worldKey, inactiveTicks);
                    return WorldState.HIBERNATING;
                }
                return WorldState.DRAINING;
            }

            if (info.state == WorldState.HIBERNATING) {
                this.ticksSaved.incrementAndGet();
                return WorldState.HIBERNATING;
            }

            return info.state;
        }
    }

    /**
     * Checks whether a given world should be ticked this cycle.
     *
     * @param worldKey Unique identifier for the world
     * @return true if world is ACTIVE or DRAINING, false if HIBERNATING
     */
    public boolean shouldTickWorld(final String worldKey) {
        if (worldKey == null) {
            return true;
        }
        final WorldTrackInfo info = this.trackedWorlds.get(worldKey);
        if (info == null) {
            return true;
        }
        return info.state != WorldState.HIBERNATING;
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
            if (info.state == WorldState.HIBERNATING || info.state == WorldState.DRAINING) {
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
        this.wakeupsTriggered.set(0);
        this.ticksSaved.set(0);
    }

    public HibernationMetrics metrics() {
        int hibernatingCount = 0;
        int activeCount = 0;
        int drainingCount = 0;

        for (final WorldTrackInfo info : this.trackedWorlds.values()) {
            switch (info.state) {
                case HIBERNATING -> hibernatingCount++;
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
            this.hibernationsTriggered.get(),
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
        int hibernatingWorlds,
        long hibernationsTriggered,
        long wakeupsTriggered,
        long worldTicksSaved
    ) {
    }
}
