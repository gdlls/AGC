package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * AGC — Deficit Round-Robin Chunk Fair Load & Send Arbiter.
 *
 * <p>When high-speed players (elytra flight, teleportation) request large bursts of new chunks,
 * naive FIFO or unbounded processing starves other players from receiving basic chunk updates.</p>
 *
 * <p>This arbiter enforces per-player FIFO queues and dispatches chunk requests using a Deficit
 * Round-Robin (DRR) algorithm, ensuring fair throughput allocation across all 500+ players.</p>
 */
public final class AgcChunkFairLoadArbiter<T> {

    public static final int DEFAULT_QUANTUM_PER_TICK = 4;

    private final Map<UUID, PlayerChunkQueue<T>> playerQueues = new ConcurrentHashMap<>();

    // Telemetry & metrics
    private final AtomicLong chunksEnqueued = new AtomicLong();
    private final AtomicLong chunksAdmitted = new AtomicLong();
    private final AtomicLong passesExecuted = new AtomicLong();

    public AgcChunkFairLoadArbiter() {}

    /**
     * Enqueues a chunk task for a specific player.
     *
     * @param playerId Player UUID
     * @param task     Chunk load/send task
     */
    public void enqueue(final UUID playerId, final T task) {
        if (playerId == null || task == null) {
            return;
        }
        final PlayerChunkQueue<T> queue = this.playerQueues.computeIfAbsent(playerId, PlayerChunkQueue::new);
        queue.tasks.offer(task);
        this.chunksEnqueued.incrementAndGet();
    }

    /**
     * Executes an arbitration round-robin pass, dispatching up to maxTotalChunks across all players.
     *
     * @param maxTotalChunks Maximum total chunk tasks to admit across all players in this pass
     * @param executor       Action executing the admitted chunk task
     * @return Number of chunks admitted and processed in this pass
     */
    public int arbitratePass(final int maxTotalChunks, final Consumer<T> executor) {
        if (maxTotalChunks <= 0 || executor == null || this.playerQueues.isEmpty()) {
            return 0;
        }

        this.passesExecuted.incrementAndGet();
        int totalDispatched = 0;
        final int limit = maxTotalChunks;

        final Iterator<Map.Entry<UUID, PlayerChunkQueue<T>>> iterator = this.playerQueues.entrySet().iterator();

        while (iterator.hasNext() && totalDispatched < limit) {
            final Map.Entry<UUID, PlayerChunkQueue<T>> entry = iterator.next();
            final PlayerChunkQueue<T> queue = entry.getValue();

            // Grant deficit quantum for this round
            queue.deficit.addAndGet(DEFAULT_QUANTUM_PER_TICK);

            while (queue.deficit.get() > 0 && totalDispatched < limit) {
                final T task = queue.tasks.poll();
                if (task == null) {
                    // Queue empty: reset deficit so idle players don't accumulate unbounded credit
                    queue.deficit.set(0);
                    break;
                }

                try {
                    executor.accept(task);
                    totalDispatched++;
                    this.chunksAdmitted.incrementAndGet();
                    queue.deficit.decrementAndGet();
                } catch (final Throwable ignored) {}
            }

            // Cleanup empty queues
            if (queue.tasks.isEmpty()) {
                iterator.remove();
            }
        }

        return totalDispatched;
    }

    public int activeQueuedPlayers() {
        return this.playerQueues.size();
    }

    public int totalPendingChunks() {
        int count = 0;
        for (final PlayerChunkQueue<T> q : this.playerQueues.values()) {
            count += q.tasks.size();
        }
        return count;
    }

    public void clear() {
        this.playerQueues.clear();
        this.chunksEnqueued.set(0);
        this.chunksAdmitted.set(0);
        this.passesExecuted.set(0);
    }

    public ArbiterMetrics metrics() {
        return new ArbiterMetrics(
            this.chunksEnqueued.get(),
            this.chunksAdmitted.get(),
            this.passesExecuted.get(),
            this.playerQueues.size(),
            this.totalPendingChunks()
        );
    }

    private static final class PlayerChunkQueue<T> {
        final UUID playerId;
        final ConcurrentLinkedQueue<T> tasks = new ConcurrentLinkedQueue<>();
        final AtomicInteger deficit = new AtomicInteger(0);

        PlayerChunkQueue(final UUID playerId) {
            this.playerId = playerId;
        }
    }

    public record ArbiterMetrics(
        long enqueued,
        long admitted,
        long passes,
        int activePlayers,
        int pendingChunks
    ) {
    }
}
