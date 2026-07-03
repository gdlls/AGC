package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Tick-local player movement cohort planner for survival-scale chunk/network work. */
public final class AGCPlayerClusterMovementPlanner {
    public static final AGCPlayerClusterMovementPlanner INSTANCE = new AGCPlayerClusterMovementPlanner();

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong clustered = new AtomicLong();
    private volatile long tickSequence;
    private volatile int targetPlayersPerCluster = 48;
    private volatile String lastReason = "cold";

    private AGCPlayerClusterMovementPlanner() {
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.targetPlayersPerCluster = this.tickSequence % 200L == 0L ? 64 : 48;
        this.lastReason = "reset targetPlayersPerCluster=" + this.targetPlayersPerCluster;
    }

    public Cluster plan(final UUID playerId, final int expectedNearbyPlayers, final String reason) {
        this.planned.incrementAndGet();
        final long seed = playerId == null ? 0L : playerId.getMostSignificantBits() ^ playerId.getLeastSignificantBits();
        final int nearby = Math.max(1, expectedNearbyPlayers);
        final int clusterSize = Math.max(1, Math.min(128, Math.max(this.targetPlayersPerCluster, nearby)));
        final long clusterId = mix(seed ^ this.tickSequence) & 0xFFFFFL;
        final int lookaheadBias = nearby >= 96 ? 3 : nearby >= 48 ? 2 : 1;
        this.clustered.incrementAndGet();
        this.lastReason = "cluster=" + clusterId + " size=" + clusterSize + " bias=" + lookaheadBias + " reason=" + safe(reason);
        return new Cluster(clusterId, clusterSize, lookaheadBias, this.lastReason);
    }

    public String statusLine() {
        return "AGCPlayerClusterMovementPlanner{tick=" + this.tickSequence
            + ", targetPlayersPerCluster=" + this.targetPlayersPerCluster
            + ", planned=" + this.planned.get()
            + ", clustered=" + this.clustered.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    public record Cluster(long clusterId, int clusterSize, int lookaheadBias, String reason) {}
}
