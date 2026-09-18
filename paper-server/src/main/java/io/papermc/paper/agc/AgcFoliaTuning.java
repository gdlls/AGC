package io.papermc.paper.agc;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Global asynchronous workload pool and world tick budget manager.
 */
public final class AgcFoliaTuning {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcFoliaTuning.class);

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicInteger WORKER_ID = new AtomicInteger();
    private static volatile ScheduledExecutorService ASYNC_POOL;

    private AgcFoliaTuning() {}

    /**
     * Initializes the asynchronous workload pool. Idempotent.
     */
    public static void bootstrap() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        final int threads = AgcHotPathCache.suggestedGlobalRegionThreads();
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "AGC-Async-Worker-" + WORKER_ID.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, throwable) ->
                LOGGER.error("Uncaught exception in AGC async worker {}", thread.getName(), throwable));
            return t;
        };
        ASYNC_POOL = Executors.newScheduledThreadPool(threads, factory);
        LOGGER.info("AGC async workload pool started with {} threads", threads);

        // Bootstrap Parallel World Tick Engine
        AgcParallelWorldTickEngine.get().bootstrap();
    }

    /**
     * Submits a task to the async pool.
     */
    public static void submitAsync(final Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        final ScheduledExecutorService pool = ASYNC_POOL;
        if (pool == null) {
            task.run();
            return;
        }
        try {
            pool.execute(task);
        } catch (final RejectedExecutionException e) {
            throw e;
        }
    }

    /**
     * Schedules a delayed task in the async pool.
     */
    public static ScheduledFuture<?> scheduleAsync(final Runnable task, final long delayMs) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (delayMs < 0L) {
            throw new IllegalArgumentException("delayMs must be >= 0");
        }
        final ScheduledExecutorService pool = ASYNC_POOL;
        if (pool == null) {
            task.run();
            return null;
        }
        return pool.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Shuts down the async workload pool.
     */
    public static void shutdown() {
        AgcParallelWorldTickEngine.get().shutdown();
        final ScheduledExecutorService pool = ASYNC_POOL;
        ASYNC_POOL = null;
        STARTED.set(false);
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                final List<Runnable> dropped = pool.shutdownNow();
                LOGGER.warn("AGC async workload pool forced shutdown, dropped {} tasks", dropped.size());
            }
        } catch (final InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Status snapshot of async workload pool. */
    public static PoolStatus status() {
        final ScheduledExecutorService pool = ASYNC_POOL;
        if (pool == null) {
            return new PoolStatus(false, 0, 0, 0);
        }
        if (pool instanceof final java.util.concurrent.ThreadPoolExecutor tpe) {
            return new PoolStatus(
                STARTED.get(),
                tpe.getCorePoolSize(),
                tpe.getActiveCount(),
                tpe.getQueue().size()
            );
        }
        return new PoolStatus(STARTED.get(), -1, -1, -1);
    }

    public record PoolStatus(boolean started, int coreSize, int activeThreads, int queueSize) {
    }

    // Per-world tick budget

    private static final ConcurrentHashMap<ServerLevel, WorldTickBudget> BUDGETS = new ConcurrentHashMap<>();

    public static WorldTickBudget budgetFor(final ServerLevel level) {
        return BUDGETS.computeIfAbsent(level, l -> new WorldTickBudget());
    }

    /**
     * Resets all world tick budget windows.
     */
    public static void resetAllBudgets() {
        for (final WorldTickBudget budget : BUDGETS.values()) {
            budget.resetTickWindow();
        }
    }

    /**
     * Clears all budget tracking on shutdown.
     */
    public static void clearBudgets() {
        BUDGETS.clear();
    }

    /**
     * Tracks world tick durations and entity/chunk counts.
     */
    public static final class WorldTickBudget {
        private final AtomicLong totalTickNanos = new AtomicLong();
        private final AtomicLong totalEntitiesTicked = new AtomicLong();
        private final AtomicLong totalChunksTicked = new AtomicLong();
        private final AtomicLong tickCount = new AtomicLong();
        private final AtomicLong lastTickStartNanos = new AtomicLong(System.nanoTime());
        private final AtomicLong lastTickDurationNanos = new AtomicLong();

        public void recordTickStart() {
            this.lastTickStartNanos.set(System.nanoTime());
        }

        public void recordTickEnd(final int entities, final int chunks) {
            final long now = System.nanoTime();
            final long started = this.lastTickStartNanos.get();
            final long durationNanos = Math.max(0L, now - started);
            this.lastTickDurationNanos.set(durationNanos);
            this.totalTickNanos.addAndGet(durationNanos);
            this.totalEntitiesTicked.addAndGet(Math.max(0, entities));
            this.totalChunksTicked.addAndGet(Math.max(0, chunks));
            this.tickCount.incrementAndGet();
        }

        /** Average tick duration in milliseconds. */
        public double averageTickMillis() {
            final long ticks = this.tickCount.get();
            if (ticks == 0L) {
                return 0.0;
            }
            return (this.totalTickNanos.get() / 1_000_000.0) / (double) ticks;
        }

        /** Duration of last tick in milliseconds. */
        public double lastTickMillis() {
            return this.lastTickDurationNanos.get() / 1_000_000.0;
        }

        public long entitiesTicked() {
            return this.totalEntitiesTicked.get();
        }

        public long chunksTicked() {
            return this.totalChunksTicked.get();
        }

        public long tickCount() {
            return this.tickCount.get();
        }

        public void resetTickWindow() {
            this.lastTickStartNanos.set(System.nanoTime());
        }
    }

    /** Returns human-readable budget report for active worlds. */
    public static List<String> budgetReport() {
        if (BUDGETS.isEmpty()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>(BUDGETS.size());
        for (final var entry : BUDGETS.entrySet()) {
            final ServerLevel level = entry.getKey();
            final WorldTickBudget budget = entry.getValue();
            if (level == null || budget == null) {
                continue;
            }
            out.add(worldLabel(level)
                + ": avg=" + String.format("%.3fms", budget.averageTickMillis())
                + " last=" + String.format("%.3fms", budget.lastTickMillis())
                + " ticks=" + budget.tickCount()
                + " entities=" + budget.entitiesTicked()
                + " chunks=" + budget.chunksTicked());
        }
        return out;
    }

    private static String worldLabel(final ServerLevel level) {
        if (level == null) {
            return "unknown";
        }
        try {
            // 1.21+ ServerLevel exposes getWorld().getName() via CraftBukkit.
            // Use toString fallback to stay decoupled from MC version churn.
            return level.toString();
        } catch (final Exception e) {
            return "world-" + System.identityHashCode(level);
        }
    }
}
