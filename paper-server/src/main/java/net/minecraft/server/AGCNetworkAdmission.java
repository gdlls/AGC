package net.minecraft.server;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Conservative network admission layer for AGC packet backpressure.
 * <p>
 * This layer never drops packets. When packet budgeting is enabled and a
 * low-priority packet exceeds its current token budget, AGC only downgrades the
 * write from writeAndFlush to write inside a one-tick deadline batch. A later
 * high-priority packet, the normal server flush path, or AGC's end-of-tick
 * deferred-flush drain will still flush the channel. Movement/combat, keepalive,
 * login/configuration and chunk/light packets keep their requested flush
 * behaviour to preserve vanilla/Paper play feel.
 */
public final class AGCNetworkAdmission {
    public static final AGCNetworkAdmission INSTANCE = new AGCNetworkAdmission();

    private static final int HIGH_PRIORITY_ESTIMATE = 96;
    private static final int CHUNK_LIGHT_ESTIMATE = 64 * 1024;
    private static final int COSMETIC_ESTIMATE = 512;
    private static final int METADATA_ESTIMATE = 256;
    private static final int DEFAULT_MAX_DEFERRED_FLUSH_DRAIN = 8192;

    private final AtomicLong requestedFlushes = new AtomicLong();
    private final AtomicLong preservedFlushes = new AtomicLong();
    private final AtomicLong downgradedFlushes = new AtomicLong();
    private final AtomicLong bypassedPackets = new AtomicLong();
    private final AtomicLong deferredFlushRequests = new AtomicLong();
    private final AtomicLong drainedDeferredFlushes = new AtomicLong();
    private final AtomicLong deadlineImmediateFlushes = new AtomicLong();
    private final AtomicLong rejectedDeferredFlushNotes = new AtomicLong();
    private final AtomicLong lastDeadlineGuardJournalNanos = new AtomicLong();
    private final ConcurrentHashMap<Class<?>, Classification> classificationCache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Connection> deferredFlushQueue = new ConcurrentLinkedQueue<>();
    private final Set<Connection> queuedDeferredFlushes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile int maxDeferredFlushPending = 16_384;
    private volatile int maxDeferredFlushDrainPerTick = DEFAULT_MAX_DEFERRED_FLUSH_DRAIN;

    private AGCNetworkAdmission() {
    }

    public void configureDeferredFlushLimits(final int maxPending, final int maxDrainPerTick) {
        this.maxDeferredFlushPending = Math.max(1, maxPending);
        this.maxDeferredFlushDrainPerTick = Math.max(1, maxDrainPerTick);
    }

    /**
     * Returns the flush flag that should be passed to the underlying connection.
     * The packet is still sent either way; false only allows Netty/Paper batching
     * to coalesce the actual socket flush for at most one server tick.
     */
    public boolean shouldFlushImmediately(final @Nullable ServerPlayer player, final Packet<?> packet, final boolean requestedFlush) {
        if (!requestedFlush || player == null || packet == null) {
            if (!requestedFlush) {
                this.bypassedPackets.incrementAndGet();
            }
            return requestedFlush;
        }
        this.requestedFlushes.incrementAndGet();

        final Classification classification = this.classify(packet);
        if (classification.priority() == AGCPacketBudget.Priority.MOVEMENT_COMBAT
            || classification.priority() == AGCPacketBudget.Priority.CHUNK_LIGHT
            || !AGCPerformanceGovernor.INSTANCE.isFeatureAllowed(AGCPerformanceGovernor.Feature.PACKET_BUDGETING)) {
            this.preservedFlushes.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.NETWORK, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, classification.packetName());
            return true;
        }

        AGCOptimizationEnvelope.INSTANCE.admitNetworkCoalescing(
            classification.packetName(),
            this.pendingDeferredFlushConnections()
        );

        final UUID playerId = player.getUUID();
        final boolean allowed = AGCPacketBudget.INSTANCE.allow(playerId, classification.priority(), classification.estimatedBytes());
        if (allowed) {
            this.preservedFlushes.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.NETWORK, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, classification.packetName());
            return true;
        }

        final AGCPlayerIntentScheduler.Admission playerIntent = AGCPlayerIntentScheduler.INSTANCE.admit(
            playerId,
            AGCPlayerIntentScheduler.IntentType.NETWORK_COSMETIC,
            Math.max(1, classification.estimatedBytes() / 256)
        );
        if (!playerIntent.admitted() || playerIntent.lane() != AGCPlayerIntentScheduler.Lane.DEADLINE_BATCH) {
            this.deadlineImmediateFlushes.incrementAndGet();
            this.preservedFlushes.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.NETWORK, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, "player intent preserved deadline " + classification.packetName());
            return true;
        }

        final AGCNoInvasionOptimizer.Decision decision = AGCNoInvasionOptimizer.INSTANCE.admitNetworkDeadlineBatch(
            classification.packetName(),
            false,
            this.pendingDeferredFlushConnections()
        );
        if (!AGCNoInvasionOptimizer.INSTANCE.isDeadlineBatch(decision)) {
            this.deadlineImmediateFlushes.incrementAndGet();
            this.preservedFlushes.incrementAndGet();
            this.recordDeadlineGuardOncePerWindow(classification.packetName());
            return true;
        }

        this.downgradedFlushes.incrementAndGet();
        return false;
    }

    /**
     * Called by Connection when AGC turns an immediate flush into a batched write.
     * The drain hook at the end of the server tick flushes each connection once,
     * preventing visual stutter or indefinitely buffered low-priority packets.
     */
    public void noteDeferredFlush(final @Nullable Connection connection) {
        if (connection == null) {
            return;
        }
        this.deferredFlushRequests.incrementAndGet();
        if (!this.canAcceptDeferredFlush() && !this.queuedDeferredFlushes.contains(connection)) {
            this.rejectedDeferredFlushNotes.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.NETWORK, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, "deferred flush note rejected by deadline guard");
            try {
                connection.flushChannel();
            } catch (final Throwable ignored) {
                // Closing connections may reject late flushes; never fail the tick.
            }
            return;
        }
        if (this.queuedDeferredFlushes.add(connection)) {
            this.deferredFlushQueue.add(connection);
        }
    }

    /**
     * Flushes connections that had low-priority writes coalesced this tick.
     * Returns the number of connection flushes requested.
     */
    public int drainDeferredFlushes() {
        return this.drainDeferredFlushes(this.maxDeferredFlushDrainPerTick);
    }

    public int drainDeferredFlushes(final int maxConnections) {
        final int limit = Math.max(1, maxConnections);
        int drained = 0;
        while (drained < limit) {
            final Connection connection = this.deferredFlushQueue.poll();
            if (connection == null) {
                break;
            }
            this.queuedDeferredFlushes.remove(connection);
            try {
                connection.flushChannel();
            } catch (final Throwable ignored) {
                // Connection may be closing/disconnected. The normal network path
                // handles disconnect cleanup; admission must never crash a tick.
            }
            drained++;
            this.drainedDeferredFlushes.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.NETWORK, AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT, "deadline flush drain");
        }
        return drained;
    }

    public Classification classify(final Packet<?> packet) {
        if (packet == null) {
            return new Classification(AGCPacketBudget.Priority.LOW_VALUE_METADATA, METADATA_ESTIMATE, "null");
        }
        return this.classificationCache.computeIfAbsent(packet.getClass(), AGCNetworkAdmission::classifyPacketClass);
    }

    private static Classification classifyPacketClass(final Class<?> packetClass) {
        final String simpleName = packetClass == null ? "unknown" : packetClass.getSimpleName();
        final String key = simpleName.toLowerCase(Locale.ROOT);

        if (containsAny(key,
            "keepalive", "disconnect", "login", "respawn", "setcarrieditem", "container", "setslot",
            "hurt", "damage", "attack", "animate", "teleport", "position", "move", "velocity", "explosion")) {
            return new Classification(AGCPacketBudget.Priority.MOVEMENT_COMBAT, HIGH_PRIORITY_ESTIMATE, simpleName);
        }
        if (containsAny(key, "chunk", "light", "blockupdate", "sectionblocksupdate", "forgetlevelchunk", "mapitemdata")) {
            return new Classification(AGCPacketBudget.Priority.CHUNK_LIGHT, CHUNK_LIGHT_ESTIMATE, simpleName);
        }
        if (containsAny(key, "particle", "sound", "levelparticles", "levelsound", "customsound", "gameevent", "titles", "boss")) {
            return new Classification(AGCPacketBudget.Priority.COSMETIC, COSMETIC_ESTIMATE, simpleName);
        }
        if (containsAny(key, "setentitydata", "setequipment", "rotatehead", "moveentity", "playerinfoupdate", "settime")) {
            return new Classification(AGCPacketBudget.Priority.LOW_VALUE_METADATA, METADATA_ESTIMATE, simpleName);
        }
        return new Classification(AGCPacketBudget.Priority.COSMETIC, COSMETIC_ESTIMATE, simpleName);
    }

    public void forget(final @Nullable ServerPlayer player) {
        if (player == null) {
            return;
        }
        AGCPacketBudget.INSTANCE.removePlayer(player.getUUID());
        AGCChunkBudget.INSTANCE.removePlayer(player.getUUID());
        AGCChunkFairQueue.INSTANCE.forget(player.getUUID());
        AGCEntityAdmission.INSTANCE.forget(player.getUUID());
        AGCEntitySnapshotPlanner.INSTANCE.forget(player.getUUID());
        AGCPlayerIntentScheduler.INSTANCE.forget(player.getUUID());
    }

    public long pendingDeferredFlushConnections() {
        return this.queuedDeferredFlushes.size();
    }

    private boolean canAcceptDeferredFlush() {
        return this.pendingDeferredFlushConnections() < this.maxDeferredFlushPending;
    }

    private void recordDeadlineGuardOncePerWindow(final String packetName) {
        final long now = System.nanoTime();
        final long previous = this.lastDeadlineGuardJournalNanos.get();
        if (now - previous < java.util.concurrent.TimeUnit.SECONDS.toNanos(5L)) {
            return;
        }
        if (this.lastDeadlineGuardJournalNanos.compareAndSet(previous, now)) {
            AGCStabilityJournal.INSTANCE.record(
                "network",
                "deferred flush deadline queue full; preserving immediate order for " + packetName
                    + " pending=" + this.pendingDeferredFlushConnections()
                    + " max=" + this.maxDeferredFlushPending
            );
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.requestedFlushes.get(),
            this.preservedFlushes.get(),
            this.downgradedFlushes.get(),
            this.bypassedPackets.get(),
            this.deferredFlushRequests.get(),
            this.drainedDeferredFlushes.get(),
            this.deadlineImmediateFlushes.get(),
            this.rejectedDeferredFlushNotes.get(),
            this.pendingDeferredFlushConnections(),
            this.maxDeferredFlushPending,
            this.maxDeferredFlushDrainPerTick,
            AGCPacketBudget.INSTANCE.snapshot()
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCNetworkAdmission{requestedFlushes=" + snapshot.requestedFlushes()
            + ", preservedFlushes=" + snapshot.preservedFlushes()
            + ", downgradedFlushes=" + snapshot.downgradedFlushes()
            + ", bypassedPackets=" + snapshot.bypassedPackets()
            + ", deferredFlushRequests=" + snapshot.deferredFlushRequests()
            + ", drainedDeferredFlushes=" + snapshot.drainedDeferredFlushes()
            + ", semanticImmediateFlushes=" + snapshot.deadlineImmediateFlushes()
            + ", rejectedDeferredFlushNotes=" + snapshot.rejectedDeferredFlushNotes()
            + ", pendingDeferredFlushConnections=" + snapshot.pendingDeferredFlushConnections()
            + ", maxPending=" + snapshot.maxDeferredFlushPending()
            + ", maxDrainPerTick=" + snapshot.maxDeferredFlushDrainPerTick()
            + ", packetBudget=" + snapshot.packetBudget()
            + '}';
    }

    private static boolean containsAny(final String key, final String... needles) {
        for (final String needle : needles) {
            if (key.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public record Classification(AGCPacketBudget.Priority priority, int estimatedBytes, String packetName) {
    }

    public record Snapshot(
        long requestedFlushes,
        long preservedFlushes,
        long downgradedFlushes,
        long bypassedPackets,
        long deferredFlushRequests,
        long drainedDeferredFlushes,
        long deadlineImmediateFlushes,
        long rejectedDeferredFlushNotes,
        long pendingDeferredFlushConnections,
        int maxDeferredFlushPending,
        int maxDeferredFlushDrainPerTick,
        AGCPacketBudget.Snapshot packetBudget
    ) {
    }
}
