package io.papermc.paper.agc.world;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Dynamic Multi-World View & Simulation Distance Engine.
 *
 * <p>Manages independent per-world View Distance and Simulation Distance tiers.
 * Decouples entity ticking radius (Simulation Distance) from chunk rendering distance
 * and scales them smoothly (1 chunk/sec) with hysteresis.</p>
 */
public final class AgcDynamicViewDistanceEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcDynamicViewDistanceEngine.class);
    private static final AgcDynamicViewDistanceEngine INSTANCE = new AgcDynamicViewDistanceEngine();

    public static final int DEFAULT_VIEW_DISTANCE = 10;
    public static final int DEFAULT_SIM_DISTANCE = 8;
    public static final int MIN_VIEW_DISTANCE = 4;
    public static final int MIN_SIM_DISTANCE = 3;

    private final ConcurrentHashMap<String, WorldDistanceProfile> worldDistances = new ConcurrentHashMap<>();

    private final AtomicLong totalAdjustments = new AtomicLong();

    public static AgcDynamicViewDistanceEngine get() {
        return INSTANCE;
    }

    private AgcDynamicViewDistanceEngine() {}

    /**
     * Evaluates and updates view and simulation distance for a specific world.
     *
     * @param worldId World identifier
     * @param playerCount Active players in this world
     * @param serverMspt Current rolling server MSPT
     * @param configuredViewDistance Configured base view distance
     * @param configuredSimDistance Configured base simulation distance
     * @return Current effective {@link WorldDistanceProfile}
     */
    public WorldDistanceProfile updateWorldDistance(
        final String worldId,
        final int playerCount,
        final double serverMspt,
        final int configuredViewDistance,
        final int configuredSimDistance
    ) {
        if (worldId == null) {
            return new WorldDistanceProfile(worldId, configuredViewDistance, configuredSimDistance);
        }

        final WorldDistanceProfile profile = this.worldDistances.computeIfAbsent(
            worldId,
            k -> new WorldDistanceProfile(k, configuredViewDistance, configuredSimDistance)
        );

        int targetView = configuredViewDistance;
        int targetSim = configuredSimDistance;

        // Performance-driven load scaling only: Never downgrade view distance if server MSPT is healthy (MSPT < 40ms)
        if (serverMspt >= 50.0) {
            targetView = Math.max(MIN_VIEW_DISTANCE, targetView - 3);
            targetSim = Math.max(MIN_SIM_DISTANCE, targetSim - 2);
        } else if (serverMspt >= 45.0) {
            targetView = Math.max(MIN_VIEW_DISTANCE, targetView - 2);
            targetSim = Math.max(MIN_SIM_DISTANCE, targetSim - 1);
        } else if (serverMspt >= 40.0) {
            targetView = Math.max(MIN_VIEW_DISTANCE, targetView - 1);
        }

        boolean changed = false;
        if (profile.currentViewDistance > targetView) {
            profile.currentViewDistance--;
            changed = true;
        } else if (profile.currentViewDistance < targetView) {
            profile.currentViewDistance++;
            changed = true;
        }

        if (profile.currentSimulationDistance > targetSim) {
            profile.currentSimulationDistance--;
            changed = true;
        } else if (profile.currentSimulationDistance < targetSim) {
            profile.currentSimulationDistance++;
            changed = true;
        }

        if (changed) {
            this.totalAdjustments.incrementAndGet();
        }

        return profile;
    }

    public WorldDistanceProfile getDistanceProfile(final String worldId) {
        if (worldId == null) return null;
        return this.worldDistances.get(worldId);
    }

    public void clear() {
        this.worldDistances.clear();
        this.totalAdjustments.set(0);
    }

    public DynamicDistanceMetrics metrics() {
        return new DynamicDistanceMetrics(
            this.worldDistances.size(),
            this.totalAdjustments.get()
        );
    }

    public static final class WorldDistanceProfile {
        final String worldId;
        public volatile int currentViewDistance;
        public volatile int currentSimulationDistance;

        WorldDistanceProfile(final String worldId, final int currentViewDistance, final int currentSimulationDistance) {
            this.worldId = worldId;
            this.currentViewDistance = currentViewDistance;
            this.currentSimulationDistance = currentSimulationDistance;
        }

        public int viewDistance() {
            return this.currentViewDistance;
        }

        public int simulationDistance() {
            return this.currentSimulationDistance;
        }
    }

    public record DynamicDistanceMetrics(
        int managedWorlds,
        long totalAdjustments
    ) {
    }
}
