package io.papermc.paper.agc.tick;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * AGC — Chunk-Batched Scheduled Block & Fluid Tick Engine.
 *
 * <p>Replaces vanilla's $O(N \log N)$ global tick iteration with an $O(1)$ bucket queue
 * partitioned by game tick and grouped by chunk coordinates for L1/L2 CPU cache locality.</p>
 */
public final class AgcBlockTickBatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcBlockTickBatcher.class);
    private static final AgcBlockTickBatcher INSTANCE = new AgcBlockTickBatcher();

    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, List<ScheduledBlockTick>>> tickBuckets = new ConcurrentHashMap<>();

    private final AtomicLong scheduledTicksEnqueued = new AtomicLong();
    private final AtomicLong scheduledTicksExecuted = new AtomicLong();
    private final AtomicLong chunkBatchesProcessed = new AtomicLong();

    public static AgcBlockTickBatcher get() {
        return INSTANCE;
    }

    private AgcBlockTickBatcher() {}

    /**
     * Schedules a block or fluid tick for execution at a specific game tick.
     *
     * @param targetTick Target server tick count
     * @param chunkX     Chunk X coordinate
     * @param chunkZ     Chunk Z coordinate
     * @param x          World block X
     * @param y          World block Y
     * @param z          World block Z
     * @param blockType  Block identifier or type handle
     */
    public void scheduleTick(
        final long targetTick,
        final int chunkX,
        final int chunkZ,
        final int x,
        final int y,
        final int z,
        final Object blockType
    ) {
        final long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        final var chunkMap = this.tickBuckets.computeIfAbsent(targetTick, k -> new ConcurrentHashMap<>());
        final var list = chunkMap.computeIfAbsent(chunkKey, k -> new ArrayList<>());

        synchronized (list) {
            list.add(new ScheduledBlockTick(x, y, z, blockType));
        }
        this.scheduledTicksEnqueued.incrementAndGet();
    }

    /**
     * Executes all scheduled block ticks queued for the current tick, grouped by chunk.
     *
     * @param currentTick  Current server tick
     * @param tickExecutor Callback processing (chunkKey, list of ticks in that chunk)
     * @return Total number of block ticks executed
     */
    public int processTicksForCurrentTick(final long currentTick, final Consumer<ScheduledBlockTick> tickExecutor) {
        final ConcurrentHashMap<Long, List<ScheduledBlockTick>> chunkMap = this.tickBuckets.remove(currentTick);
        if (chunkMap == null || chunkMap.isEmpty()) {
            return 0;
        }

        int executed = 0;
        for (final Map.Entry<Long, List<ScheduledBlockTick>> entry : chunkMap.entrySet()) {
            final List<ScheduledBlockTick> ticks = entry.getValue();
            if (ticks == null || ticks.isEmpty()) continue;

            this.chunkBatchesProcessed.incrementAndGet();
            for (final ScheduledBlockTick tick : ticks) {
                if (tickExecutor != null) {
                    try {
                        tickExecutor.accept(tick);
                    } catch (final Throwable t) {
                        LOGGER.warn("Failed executing scheduled block tick at ({}, {}, {})", tick.x, tick.y, tick.z, t);
                    }
                }
                executed++;
            }
        }

        this.scheduledTicksExecuted.addAndGet(executed);
        return executed;
    }

    public void clear() {
        this.tickBuckets.clear();
        this.scheduledTicksEnqueued.set(0);
        this.scheduledTicksExecuted.set(0);
        this.chunkBatchesProcessed.set(0);
    }

    public BatcherMetrics metrics() {
        return new BatcherMetrics(
            this.tickBuckets.size(),
            this.scheduledTicksEnqueued.get(),
            this.scheduledTicksExecuted.get(),
            this.chunkBatchesProcessed.get()
        );
    }

    public record ScheduledBlockTick(int x, int y, int z, Object type) {
    }

    public record BatcherMetrics(
        int pendingTickBuckets,
        long scheduledTicksEnqueued,
        long scheduledTicksExecuted,
        long chunkBatchesProcessed
    ) {
    }
}
