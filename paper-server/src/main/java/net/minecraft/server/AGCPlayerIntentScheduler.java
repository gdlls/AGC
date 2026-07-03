package net.minecraft.server;

import java.util.EnumMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-player no-invasion intent scheduler.
 * <p>
 * It does not execute game logic. It hands out deterministic tickets and lane
 * decisions so packet, chunk, entity and arena helpers can reduce allocation or
 * batch low-value work without reordering a player's visible stream. Every
 * player keeps FIFO order inside each intent type; expensive work waits for the
 * next tick rather than jumping ahead.
 */
public final class AGCPlayerIntentScheduler {
    public static final AGCPlayerIntentScheduler INSTANCE = new AGCPlayerIntentScheduler();

    public enum IntentType {
        NETWORK_INTERACTIVE,
        NETWORK_COSMETIC,
        CHUNK_SEND,
        CHUNK_LOAD,
        CHUNK_GENERATE,
        ENTITY_TRACKER_SNAPSHOT,
        ARENA_FLOW,
        PLUGIN_COMMIT
    }

    public enum Lane {
        ORDERED_NOW,
        DEADLINE_BATCH,
        FIFO_WAIT,
        READ_ONLY_PREPARE,
        ORDERED_COMMIT
    }

    private final ConcurrentHashMap<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final AtomicLong globalTickets = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong waited = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int networkCosmeticBudget = 256;
    private volatile int chunkBudget = 96;
    private volatile int entitySnapshotBudget = 4096;
    private volatile int arenaBudget = 512;
    private volatile long tickSequence;

    private AGCPlayerIntentScheduler() {
    }

    public void configure(final boolean enabled, final int networkCosmeticBudget, final int chunkBudget, final int entitySnapshotBudget, final int arenaBudget) {
        this.enabled = enabled;
        this.networkCosmeticBudget = Math.max(1, networkCosmeticBudget);
        this.chunkBudget = Math.max(1, chunkBudget);
        this.entitySnapshotBudget = Math.max(128, entitySnapshotBudget);
        this.arenaBudget = Math.max(1, arenaBudget);
    }

    public void beginTick(final long tickSequence) {
        this.tickSequence = Math.max(0L, tickSequence);
        for (final PlayerState state : this.players.values()) {
            state.refill(this.networkCosmeticBudget, this.chunkBudget, this.entitySnapshotBudget, this.arenaBudget);
        }
    }

    public Admission admit(final UUID playerId, final IntentType type, final int cost) {
        final long ticket = this.globalTickets.incrementAndGet();
        if (!this.enabled || playerId == null || type == IntentType.NETWORK_INTERACTIVE || type == IntentType.PLUGIN_COMMIT) {
            this.admitted.incrementAndGet();
            return new Admission(true, Lane.ORDERED_NOW, ticket, Integer.MAX_VALUE, "ordered immediate");
        }
        final PlayerState state = this.players.computeIfAbsent(playerId, ignored -> new PlayerState());
        final int normalizedCost = Math.max(1, cost);
        final int remaining = state.tryConsume(type, normalizedCost);
        if (remaining < 0) {
            this.waited.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(
                AGCSemanticInvariant.Domain.PLAYER,
                AGCSemanticInvariant.Decision.CONFLICT_SERIALISED,
                "intent-fifo-wait type=" + type + " ticket=" + ticket
            );
            return new Admission(false, Lane.FIFO_WAIT, ticket, 0, "per-player FIFO budget exhausted");
        }
        this.admitted.incrementAndGet();
        final Lane lane = switch (type) {
            case NETWORK_COSMETIC -> Lane.DEADLINE_BATCH;
            case ENTITY_TRACKER_SNAPSHOT -> Lane.READ_ONLY_PREPARE;
            case ARENA_FLOW -> Lane.ORDERED_COMMIT;
            case CHUNK_SEND, CHUNK_LOAD, CHUNK_GENERATE -> Lane.ORDERED_COMMIT;
            default -> Lane.ORDERED_NOW;
        };
        AGCSemanticInvariant.INSTANCE.record(
            AGCSemanticInvariant.Domain.PLAYER,
            lane == Lane.READ_ONLY_PREPARE ? AGCSemanticInvariant.Decision.READ_ONLY_PARALLEL_PREPARE : AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT,
            "intent-admit type=" + type + " lane=" + lane + " ticket=" + ticket
        );
        return new Admission(true, lane, ticket, remaining, "admitted");
    }

    public void forget(final UUID playerId) {
        if (playerId != null) {
            this.players.remove(playerId);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.tickSequence,
            this.players.size(),
            this.globalTickets.get(),
            this.admitted.get(),
            this.waited.get(),
            this.networkCosmeticBudget,
            this.chunkBudget,
            this.entitySnapshotBudget,
            this.arenaBudget
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCPlayerIntentScheduler{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", players=" + snapshot.players()
            + ", tickets=" + snapshot.tickets()
            + ", admitted=" + snapshot.admitted()
            + ", waited=" + snapshot.waited()
            + ", budgets(network/chunk/entity/arena)=" + snapshot.networkCosmeticBudget() + '/' + snapshot.chunkBudget() + '/' + snapshot.entitySnapshotBudget() + '/' + snapshot.arenaBudget()
            + '}';
    }

    private static final class PlayerState {
        private final EnumMap<IntentType, Integer> tokens = new EnumMap<>(IntentType.class);

        PlayerState() {
            this.refill(256, 96, 4096, 512);
        }

        synchronized void refill(final int networkCosmeticBudget, final int chunkBudget, final int entitySnapshotBudget, final int arenaBudget) {
            this.tokens.put(IntentType.NETWORK_COSMETIC, networkCosmeticBudget);
            this.tokens.put(IntentType.CHUNK_SEND, chunkBudget);
            this.tokens.put(IntentType.CHUNK_LOAD, chunkBudget);
            this.tokens.put(IntentType.CHUNK_GENERATE, Math.max(1, chunkBudget / 3));
            this.tokens.put(IntentType.ENTITY_TRACKER_SNAPSHOT, entitySnapshotBudget);
            this.tokens.put(IntentType.ARENA_FLOW, arenaBudget);
        }

        synchronized int tryConsume(final IntentType type, final int cost) {
            final int current = this.tokens.getOrDefault(type, Integer.MAX_VALUE);
            if (current < cost) {
                return -1;
            }
            final int remaining = current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current - cost;
            this.tokens.put(type, remaining);
            return remaining;
        }
    }

    public record Admission(boolean admitted, Lane lane, long ticket, int remainingBudget, String reason) {
    }

    public record Snapshot(boolean enabled, long tickSequence, int players, long tickets, long admitted, long waited, int networkCosmeticBudget, int chunkBudget, int entitySnapshotBudget, int arenaBudget) {
    }
}
