package net.minecraft.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic shard planner for very large multi-world and mini-game layouts.
 * <p>
 * A shard is only a planning/queueing label: it does not move worlds to another
 * thread by itself and does not change Bukkit visibility or world state. It lets
 * AGC distribute read-only preparation, chunk prewarm and player fanout in a
 * stable order so thousands of players do not all compete for one global queue.
 */
public final class AGCShardPlanner {
    public static final AGCShardPlanner INSTANCE = new AGCShardPlanner();

    private final Map<String, Integer> explicitShards = Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicLong assignments = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int playersPerShard = 64;
    private volatile int maxShards = 4096;

    private AGCShardPlanner() {
    }

    public void configure(final boolean enabled, final int playersPerShard, final int maxShards) {
        this.enabled = enabled;
        this.playersPerShard = Math.max(1, playersPerShard);
        this.maxShards = Math.max(1, maxShards);
    }

    public int shardForPlayer(final UUID playerId) {
        if (!this.enabled || playerId == null) {
            return 0;
        }
        final long mixed = playerId.getMostSignificantBits() ^ Long.rotateLeft(playerId.getLeastSignificantBits(), 17);
        return Math.floorMod((int) (mixed ^ (mixed >>> 32)), this.maxShards);
    }

    public int shardForKey(final String key) {
        if (!this.enabled || key == null || key.isBlank()) {
            return 0;
        }
        synchronized (this.explicitShards) {
            return this.explicitShards.computeIfAbsent(key, ignored -> {
                this.assignments.incrementAndGet();
                return Math.floorMod(key.hashCode(), this.maxShards);
            });
        }
    }

    public Plan plan(final int expectedPlayers, final int activeWorlds, final int activeArenas) {
        final int players = Math.max(0, expectedPlayers);
        final int shardsByPlayers = Math.max(1, (players + this.playersPerShard - 1) / this.playersPerShard);
        final int shardsByWorlds = Math.max(1, activeWorlds);
        final int shardsByArenas = Math.max(1, activeArenas);
        final int recommended = Math.min(this.maxShards, Math.max(shardsByPlayers, Math.max(shardsByWorlds, shardsByArenas)));
        final ArrayList<Integer> ids = new ArrayList<>(recommended);
        for (int i = 0; i < recommended; i++) {
            ids.add(i);
        }
        return new Plan(this.enabled, players, activeWorlds, activeArenas, recommended, Collections.unmodifiableList(ids));
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.playersPerShard, this.maxShards, this.explicitShards.size(), this.assignments.get());
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCShardPlanner{enabled=" + snapshot.enabled()
            + ", playersPerShard=" + snapshot.playersPerShard()
            + ", maxShards=" + snapshot.maxShards()
            + ", explicitKeys=" + snapshot.explicitKeys()
            + ", assignments=" + snapshot.assignments()
            + '}';
    }

    public record Plan(boolean enabled, int expectedPlayers, int activeWorlds, int activeArenas, int recommendedShards, List<Integer> shardIds) {
    }

    public record Snapshot(boolean enabled, int playersPerShard, int maxShards, int explicitKeys, long assignments) {
    }
}
