package io.papermc.paper.agc.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Chunk Packet Transmission & Compression Cache Optimizer.
 *
 * <p>Caches serialized and compressed chunk payloads to avoid duplicate CPU work
 * when multiple players load identical chunks concurrently. Also prioritizes chunks
 * aligned with player look directions.</p>
 */
public final class AgcChunkPacketOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcChunkPacketOptimizer.class);
    private static final AgcChunkPacketOptimizer INSTANCE = new AgcChunkPacketOptimizer();

    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, CachedChunkPacket>> packetCache = new ConcurrentHashMap<>();

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong compressedBytesSaved = new AtomicLong();

    public static AgcChunkPacketOptimizer get() {
        return INSTANCE;
    }

    private AgcChunkPacketOptimizer() {}

    /**
     * Retrieves or caches a serialized chunk packet payload.
     *
     * @param worldId World identifier
     * @param chunkX  Chunk X coordinate
     * @param chunkZ  Chunk Z coordinate
     * @param encoder Function encoding and compressing raw chunk data if absent
     * @return Cached or freshly encoded byte array
     */
    public byte[] getOrEncodeChunkPacket(
        final String worldId,
        final int chunkX,
        final int chunkZ,
        final java.util.function.Supplier<byte[]> encoder
    ) {
        if (worldId == null || encoder == null) {
            return encoder != null ? encoder.get() : new byte[0];
        }

        final long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        final ConcurrentHashMap<Long, CachedChunkPacket> worldMap = this.packetCache.computeIfAbsent(
            worldId, k -> new ConcurrentHashMap<>()
        );

        final CachedChunkPacket cached = worldMap.get(chunkKey);
        if (cached != null && !cached.isExpired()) {
            this.cacheHits.incrementAndGet();
            this.compressedBytesSaved.addAndGet(cached.data().length);
            return cached.data();
        }

        this.cacheMisses.incrementAndGet();
        final byte[] encoded = encoder.get();
        if (encoded != null && encoded.length > 0) {
            worldMap.put(chunkKey, new CachedChunkPacket(encoded, System.currentTimeMillis()));
        }
        return encoded != null ? encoded : new byte[0];
    }

    /**
     * Invalidate chunk packet cache when chunk blocks change.
     */
    public void invalidate(final String worldId, final int chunkX, final int chunkZ) {
        if (worldId == null) return;
        final ConcurrentHashMap<Long, CachedChunkPacket> worldMap = this.packetCache.get(worldId);
        if (worldMap != null) {
            final long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
            worldMap.remove(chunkKey);
        }
    }

    /**
     * Calculates the transmission priority score of a chunk relative to a player's position and look direction.
     * Higher score indicates higher transmission priority (e.g. directly in front of player).
     *
     * @param playerChunkX Player's current chunk X
     * @param playerChunkZ Player's current chunk Z
     * @param playerYawRad Player look angle in radians (yaw)
     * @param targetChunkX Target chunk X
     * @param targetChunkZ Target chunk Z
     * @return Priority score (positive = ahead, negative = behind)
     */
    public double computeChunkPriority(
        final int playerChunkX,
        final int playerChunkZ,
        final float playerYawRad,
        final int targetChunkX,
        final int targetChunkZ
    ) {
        final double dx = targetChunkX - playerChunkX;
        final double dz = targetChunkZ - playerChunkZ;
        final double distSq = dx * dx + dz * dz;

        if (distSq == 0) {
            return 1000.0; // Player's own chunk has maximum priority
        }

        final double invDist = 1.0 / Math.sqrt(distSq);
        final double dirX = dx * invDist;
        final double dirZ = dz * invDist;

        final double lookX = -Math.sin(playerYawRad);
        final double lookZ = Math.cos(playerYawRad);

        final double dot = lookX * dirX + lookZ * dirZ;

        return (100.0 / Math.max(1.0, Math.sqrt(distSq))) + (dot * 25.0);
    }

    public void clear() {
        this.packetCache.clear();
        this.cacheHits.set(0);
        this.cacheMisses.set(0);
        this.compressedBytesSaved.set(0);
    }

    public ChunkPacketMetrics metrics() {
        int totalCached = 0;
        for (final ConcurrentHashMap<Long, CachedChunkPacket> m : this.packetCache.values()) {
            totalCached += m.size();
        }
        return new ChunkPacketMetrics(
            this.packetCache.size(),
            totalCached,
            this.cacheHits.get(),
            this.cacheMisses.get(),
            this.compressedBytesSaved.get()
        );
    }

    private record CachedChunkPacket(byte[] data, long timestamp) {
        public boolean isExpired() {
            return (System.currentTimeMillis() - this.timestamp) > 5000; // 5 second TTL
        }
    }

    public record ChunkPacketMetrics(
        int trackedWorlds,
        int totalCachedPackets,
        long cacheHits,
        long cacheMisses,
        long compressedBytesSaved
    ) {
        public double hitRatio() {
            final long total = this.cacheHits + this.cacheMisses;
            return total > 0 ? (double) this.cacheHits / (double) total : 0.0;
        }
    }
}
