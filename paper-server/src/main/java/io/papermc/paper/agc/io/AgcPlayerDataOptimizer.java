package io.papermc.paper.agc.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Differential Player Data Serialization & Priority Save Engine.
 *
 * <p>Minimizes disk serialization penalties for player data:
 * <ul>
 *   <li><b>Differential Saving:</b> Tracks dirty bitmasks to skip reserializing unchanged inventory/attribute NBT tags.</li>
 *   <li><b>Urgent Disconnect Flushes:</b> Prioritizes disconnecting player saves to prevent inventory rollback on server crashes.</li>
 * </ul>
 * </p>
 */
public final class AgcPlayerDataOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcPlayerDataOptimizer.class);
    private static final AgcPlayerDataOptimizer INSTANCE = new AgcPlayerDataOptimizer();

    public static final int DIRTY_INVENTORY = 1 << 0;
    public static final int DIRTY_POSITION = 1 << 1;
    public static final int DIRTY_HEALTH_STATS = 1 << 2;
    public static final int DIRTY_ENDERCHEST = 1 << 3;
    public static final int DIRTY_ALL = 0xFF;

    private final ConcurrentHashMap<UUID, Integer> playerDirtyMasks = new ConcurrentHashMap<>();

    private final AtomicLong playerSavesProcessed = new AtomicLong();
    private final AtomicLong differentialSavesFastPath = new AtomicLong();
    private final AtomicLong disconnectUrgentFlushes = new AtomicLong();

    public static AgcPlayerDataOptimizer get() {
        return INSTANCE;
    }

    private AgcPlayerDataOptimizer() {}

    /**
     * Marks a player attribute or inventory dirty.
     */
    public void markDirty(final UUID playerId, final int dirtyFlag) {
        if (playerId == null) return;
        this.playerDirtyMasks.compute(playerId, (k, mask) -> (mask == null ? 0 : mask) | dirtyFlag);
    }

    /**
     * Checks if player data is dirty and clears dirty bitmask upon saving.
     */
    public boolean shouldSaveAndClearDirty(final UUID playerId, final boolean isDisconnect) {
        if (playerId == null) return false;
        this.playerSavesProcessed.incrementAndGet();

        if (isDisconnect) {
            this.disconnectUrgentFlushes.incrementAndGet();
            this.playerDirtyMasks.remove(playerId);
            return true;
        }

        final Integer mask = this.playerDirtyMasks.remove(playerId);
        if (mask == null || mask == 0) {
            this.differentialSavesFastPath.incrementAndGet();
            return false; // Clean -> skip serialization
        }

        return true;
    }

    public void clear() {
        this.playerDirtyMasks.clear();
        this.playerSavesProcessed.set(0);
        this.differentialSavesFastPath.set(0);
        this.disconnectUrgentFlushes.set(0);
    }

    public PlayerDataOptimizerMetrics metrics() {
        return new PlayerDataOptimizerMetrics(
            this.playerDirtyMasks.size(),
            this.playerSavesProcessed.get(),
            this.differentialSavesFastPath.get(),
            this.disconnectUrgentFlushes.get()
        );
    }

    public record PlayerDataOptimizerMetrics(
        int trackedDirtyPlayers,
        long playerSavesProcessed,
        long differentialSavesFastPath,
        long disconnectUrgentFlushes
    ) {
    }
}
