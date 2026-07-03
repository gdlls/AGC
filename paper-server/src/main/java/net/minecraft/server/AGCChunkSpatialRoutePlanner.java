package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stable chunk-route planner for ordinary survival movement.
 * It only prepares read-only route hints; it never reorders Paper's chunk queue.
 */
public final class AGCChunkSpatialRoutePlanner {
    public static final AGCChunkSpatialRoutePlanner INSTANCE = new AGCChunkSpatialRoutePlanner();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile long lastRouteKey;
    private volatile String lastReason = "cold";

    private AGCChunkSpatialRoutePlanner() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastRouteKey = 0L;
        this.lastReason = "tick reset";
    }

    public Plan plan(final UUID playerId, final AGCChunkBudget.Operation operation, final int chunkX, final int chunkZ, final int radius, final String reason) {
        this.requested.incrementAndGet();
        final int safeRadius = Math.max(1, Math.min(32, radius));
        final long key = stableRouteKey(playerId, chunkX, chunkZ);
        this.lastRouteKey = key;
        final AGCUnifiedTickPlanCompiler.Admission admission = AGCUnifiedTickPlanCompiler.INSTANCE.admit(
            AGCUnifiedTickPlanCompiler.Lane.CHUNK_INTENT,
            Math.max(1L, safeRadius * 2L),
            false,
            "chunk-route " + (operation == null ? "UNKNOWN" : operation.name()) + ' ' + safe(reason)
        );
        if (!admission.readOnlyPrepare()) {
            this.ordered.incrementAndGet();
            this.lastReason = admission.reason();
            return new Plan(false, key, safeRadius, 0, admission.reason());
        }
        final int hints = Math.max(1, Math.min(4096, safeRadius * safeRadius + safeRadius));
        this.planned.incrementAndGet();
        this.lastReason = "stable route hints=" + hints + " key=" + key;
        return new Plan(true, key, safeRadius, hints, this.lastReason);
    }

    public static long stableRouteKey(final UUID playerId, final int chunkX, final int chunkZ) {
        long seed = playerId == null ? 0x9E3779B97F4A7C15L : playerId.getMostSignificantBits() ^ Long.rotateLeft(playerId.getLeastSignificantBits(), 17);
        long x = chunkX & 0xffffffffL;
        long z = chunkZ & 0xffffffffL;
        long morton = interleave16((int) x) | (interleave16((int) z) << 1);
        return seed ^ Long.rotateLeft(morton, 23) ^ ((long) chunkX * 0xD6E8FEB86659FD93L) ^ ((long) chunkZ * 0xA5A3564E27F88613L);
    }

    private static long interleave16(final int value) {
        long x = value & 0xffffL;
        x = (x | (x << 8)) & 0x00FF00FFL;
        x = (x | (x << 4)) & 0x0F0F0F0FL;
        x = (x | (x << 2)) & 0x33333333L;
        x = (x | (x << 1)) & 0x55555555L;
        return x;
    }

    public String statusLine() {
        return "AGCChunkSpatialRoutePlanner{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", planned=" + this.planned.get()
            + ", ordered=" + this.ordered.get()
            + ", lastRouteKey=" + this.lastRouteKey
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String reason) { return reason == null || reason.isBlank() ? "unspecified" : reason; }
    public record Plan(boolean admitted, long routeKey, int radius, int hints, String reason) {}
}
