package io.papermc.paper.agc.network;

import io.netty.buffer.ByteBuf;
import io.papermc.paper.agc.AgcPacketBroadcastDeduplicator;
import io.papermc.paper.agc.AgcZeroCopyBroadcastHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * AGC — High-Performance Packet Broadcast & Fan-Out Optimizer.
 *
 * <p>Implements packet LOD (Level of Detail), chunk-based broadcast grouping,
 * and zero-copy fan-out across 5,000+ connected players.</p>
 */
public final class AgcBroadcastOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcBroadcastOptimizer.class);
    private static final AgcBroadcastOptimizer INSTANCE = new AgcBroadcastOptimizer();

    public enum PacketLodTier {
        /** Full fidelity (<= 16 blocks): all metadata, animations, full precision movement. */
        TIER_0_FULL(16.0, 1),
        /** Medium distance (<= 48 blocks): position, rotation, animations, 2-tick metadata. */
        TIER_1_MEDIUM(48.0, 2),
        /** Far distance (<= 96 blocks): position only, 4-tick metadata. */
        TIER_2_FAR(96.0, 4),
        /** Extreme distance (> 96 blocks): position only, 8-tick metadata. */
        TIER_3_VERY_FAR(Double.MAX_VALUE, 8);

        private final double maxDistance;
        private final int metadataIntervalTicks;

        PacketLodTier(final double maxDistance, final int metadataIntervalTicks) {
            this.maxDistance = maxDistance;
            this.metadataIntervalTicks = metadataIntervalTicks;
        }

        public double maxDistance() {
            return this.maxDistance;
        }

        public int metadataIntervalTicks() {
            return this.metadataIntervalTicks;
        }
    }

    // Chunk-based viewer tracking for zero-copy broadcasting
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Object, Boolean>> chunkViewers = new ConcurrentHashMap<>();

    private final AtomicLong totalBroadcasts = new AtomicLong();
    private final AtomicLong totalFanOutRecipients = new AtomicLong();
    private final AtomicLong lodFilteredPackets = new AtomicLong();
    private final AtomicLong coalescedUpdates = new AtomicLong();

    public static AgcBroadcastOptimizer get() {
        return INSTANCE;
    }

    private AgcBroadcastOptimizer() {}

    /**
     * Resolves the Packet LOD tier for an entity relative to a viewing player.
     *
     * @param distanceSquared Squared distance between viewer and entity
     * @return Resolved {@link PacketLodTier}
     */
    public PacketLodTier resolveLodTier(final double distanceSquared) {
        if (distanceSquared <= 16.0 * 16.0) {
            return PacketLodTier.TIER_0_FULL;
        } else if (distanceSquared <= 48.0 * 48.0) {
            return PacketLodTier.TIER_1_MEDIUM;
        } else if (distanceSquared <= 96.0 * 96.0) {
            return PacketLodTier.TIER_2_FAR;
        } else {
            return PacketLodTier.TIER_3_VERY_FAR;
        }
    }

    /**
     * Determines whether metadata updates should be dispatched for a viewer under a specific LOD tier.
     *
     * @param tier LOD tier
     * @param currentTick Current server tick count
     * @return {@code true} if metadata packet should be dispatched
     */
    public boolean shouldSendMetadata(final PacketLodTier tier, final long currentTick) {
        if (tier == null || tier == PacketLodTier.TIER_0_FULL) {
            return true;
        }
        final boolean send = (currentTick % tier.metadataIntervalTicks()) == 0;
        if (!send) {
            this.lodFilteredPackets.incrementAndGet();
        }
        return send;
    }

    /**
     * Registers a viewer player connection into a chunk's viewer set.
     */
    public void registerChunkViewer(final int chunkX, final int chunkZ, final Object viewerConnection) {
        if (viewerConnection == null) return;
        final long key = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        this.chunkViewers.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(viewerConnection, Boolean.TRUE);
    }

    /**
     * Unregisters a viewer player connection from a chunk's viewer set.
     */
    public void unregisterChunkViewer(final int chunkX, final int chunkZ, final Object viewerConnection) {
        if (viewerConnection == null) return;
        final long key = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        final ConcurrentHashMap<Object, Boolean> viewers = this.chunkViewers.get(key);
        if (viewers != null) {
            viewers.remove(viewerConnection);
            if (viewers.isEmpty()) {
                this.chunkViewers.remove(key, viewers);
            }
        }
    }

    /**
     * Performs a zero-copy broadcast of a serialized buffer to all viewers of a specific chunk.
     */
    @SuppressWarnings("unchecked")
    public <T> void broadcastToChunkViewers(
        final int chunkX,
        final int chunkZ,
        final ByteBuf payload,
        final Consumer<T> sender
    ) {
        if (payload == null || sender == null) return;
        final long key = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        final ConcurrentHashMap<Object, Boolean> viewers = this.chunkViewers.get(key);
        if (viewers == null || viewers.isEmpty()) return;

        final Collection<Object> keys = viewers.keySet();
        this.totalBroadcasts.incrementAndGet();
        this.totalFanOutRecipients.addAndGet(keys.size());

        AgcPacketBroadcastDeduplicator.get().broadcastZeroCopy(payload, keys, obj -> sender.accept((T) obj));
    }

    public void recordCoalescedUpdate() {
        this.coalescedUpdates.incrementAndGet();
    }

    public void clear() {
        this.chunkViewers.clear();
        this.totalBroadcasts.set(0);
        this.totalFanOutRecipients.set(0);
        this.lodFilteredPackets.set(0);
        this.coalescedUpdates.set(0);
    }

    public OptimizerMetrics metrics() {
        return new OptimizerMetrics(
            this.chunkViewers.size(),
            this.totalBroadcasts.get(),
            this.totalFanOutRecipients.get(),
            this.lodFilteredPackets.get(),
            this.coalescedUpdates.get()
        );
    }

    public record OptimizerMetrics(
        int trackedChunksWithViewers,
        long totalBroadcasts,
        long totalFanOutRecipients,
        long lodFilteredPackets,
        long coalescedUpdates
    ) {
    }
}
