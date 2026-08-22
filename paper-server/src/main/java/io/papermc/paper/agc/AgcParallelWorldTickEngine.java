package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * AGC — High-Performance Parallel World Tick Engine.
 *
 * <p>Provides multi-core parallel world ticking for 50+ active worlds on a single Paper instance.
 * Partitions worlds into conflict-free parallel execution waves, dispatches them across a pool
 * of dedicated {@code TickThread} workers plus the primary server thread, enforces barrier
 * synchronization, and coordinates post-barrier cross-world mutation draining.</p>
 */
public final class AgcParallelWorldTickEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcParallelWorldTickEngine.class);
    private static final AgcParallelWorldTickEngine INSTANCE = new AgcParallelWorldTickEngine();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger workerId = new AtomicInteger();
    private volatile ExecutorService workerPool;
    private volatile int configuredWorkers = 0;

    // Concurrency state
    private volatile boolean parallelPhaseActive = false;
    private final ThreadLocal<Object> currentTickingWorld = new ThreadLocal<>();

    // Telemetry & metrics
    private final AtomicLong parallelTicksExecuted = new AtomicLong();
    private final AtomicLong sequentialTicksExecuted = new AtomicLong();
    private final AtomicLong totalWavesExecuted = new AtomicLong();
    private final AtomicLong totalWaveNanos = new AtomicLong();
    private final AtomicLong parallelFailures = new AtomicLong();

    public static AgcParallelWorldTickEngine get() {
        return INSTANCE;
    }

    private AgcParallelWorldTickEngine() {}

    /**
     * Initializes the parallel world tick worker pool.
     */
    public synchronized void bootstrap() {
        if (!this.started.compareAndSet(false, true)) {
            return;
        }
        final int workers = resolveOptimalWorkerCount();
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "AGC-WorldTick-Worker-" + this.workerId.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, throwable) ->
                LOGGER.error("Uncaught exception in AGC world tick worker {}", thread.getName(), throwable));
            return t;
        };
        this.configuredWorkers = workers;
        this.workerPool = Executors.newFixedThreadPool(workers, factory);
        LOGGER.info("AGC Parallel World Tick Engine initialized with {} worker threads", workers);
    }

    /**
     * Shuts down the parallel world tick worker pool.
     */
    public synchronized void shutdown() {
        this.started.set(false);
        final ExecutorService pool = this.workerPool;
        this.workerPool = null;
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (final InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Ticks a collection of worlds using parallel waves or sequential fallback.
     *
     * @param worlds        List of world handles to tick
     * @param ticker        Per-world tick consumer
     * @param minWorlds     Minimum number of worlds required to engage parallel mode
     * @param <T>           World context type
     * @return ExecutionSummary containing timing and wave statistics
     */
    public <T> ExecutionSummary executeWorldTicks(
        final List<T> worlds,
        final Consumer<T> ticker,
        final int minWorlds
    ) {
        return this.executeWorldTicks(worlds, ticker, minWorlds, null);
    }

    /**
     * Ticks a collection of worlds using parallel waves or sequential fallback.
     *
     * @param worlds        List of world handles to tick
     * @param ticker        Per-world tick consumer
     * @param minWorlds     Minimum number of worlds required to engage parallel mode
     * @param conflicts     Optional pairwise predicate; worlds that "conflict" (e.g. share portal
     *                      linkage or pending cross-world transfers) are never scheduled into the
     *                      same wave. {@code null} disables conflict avoidance.
     * @param <T>           World context type
     * @return ExecutionSummary containing timing and wave statistics
     */
    public <T> ExecutionSummary executeWorldTicks(
        final List<T> worlds,
        final Consumer<T> ticker,
        final int minWorlds,
        final java.util.function.BiPredicate<? super T, ? super T> conflicts
    ) {
        if (worlds == null || worlds.isEmpty()) {
            return ExecutionSummary.EMPTY;
        }

        final int worldCount = worlds.size();
        // AGC fix: gate on PARALLEL_WORLD_TICK (AGGRESSIVE_BUT_SAFE), not MULTIWORLD_UNLOAD (BASELINE).
        // The previous gate allowed multi-core parallel world ticking to engage in the default
        // AGC_BASELINE mode, firing Bukkit events off the primary thread and breaking the
        // "baseline = 100% plugin compatibility" contract.
        final boolean allowParallel = AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_WORLD_TICK)
            && this.started.get()
            && this.workerPool != null
            && worldCount >= Math.max(2, minWorlds);

        if (!allowParallel) {
            // Sequential execution path
            final long start = System.nanoTime();
            for (final T world : worlds) {
                ticker.accept(world);
            }
            final long duration = System.nanoTime() - start;
            this.sequentialTicksExecuted.incrementAndGet();
            return new ExecutionSummary(false, 1, worldCount, duration);
        }

        // Parallel wave execution path
        final long start = System.nanoTime();
        this.parallelPhaseActive = true;
        int waveCount = 0;
        Throwable tickFailure = null;

        try {
            final List<List<T>> waves = partitionIntoWaves(worlds, this.configuredWorkers + 1, conflicts);
            waveCount = waves.size();

            for (final List<T> wave : waves) {
                final long waveStart = System.nanoTime();
                final int waveSize = wave.size();
                if (waveSize == 1) {
                    // Single world in wave: tick on primary thread directly
                    tickOwnedWorld(wave.get(0), ticker);
                } else {
                    // Multi-world wave: submit 1..waveSize-1 to worker pool, primary thread ticks wave[0]
                    final List<Future<?>> futures = new ArrayList<>(waveSize - 1);
                    for (int i = 1; i < waveSize; i++) {
                        final T world = wave.get(i);
                        futures.add(this.workerPool.submit(() -> {
                            tickOwnedWorld(world, ticker);
                            return null;
                        }));
                    }

                    // Primary thread ticks wave[0] in parallel
                    try {
                        tickOwnedWorld(wave.get(0), ticker);
                    } catch (final Throwable t) {
                        if (tickFailure == null) tickFailure = t;
                    }

                    // Await barrier for all worker tasks in this wave
                    for (final Future<?> f : futures) {
                        try {
                            f.get();
                        } catch (final ExecutionException ee) {
                            if (tickFailure == null) tickFailure = ee.getCause() != null ? ee.getCause() : ee;
                        } catch (final InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            if (tickFailure == null) tickFailure = ie;
                        }
                    }
                }
                this.totalWaveNanos.addAndGet(System.nanoTime() - waveStart);
                this.totalWavesExecuted.incrementAndGet();
            }
        } finally {
            this.parallelPhaseActive = false;
        }

        // AGC fix: cross-world queue draining is intentionally NOT performed here.
        // Draining used to happen both inside this method and again in MinecraftServer#tickChildren,
        // producing duplicate drain passes. The single authoritative drain site is now the
        // post-world-tick section of MinecraftServer#tickChildren, which also covers the sequential
        // execution path (this method's early-return path never drained at all).

        final long duration = System.nanoTime() - start;
        this.parallelTicksExecuted.incrementAndGet();

        if (tickFailure != null) {
            this.parallelFailures.incrementAndGet();
            LOGGER.error("Error occurred during parallel world tick wave", tickFailure);
            if (tickFailure instanceof RuntimeException re) throw re;
            if (tickFailure instanceof Error err) throw err;
            throw new RuntimeException(tickFailure);
        }

        return new ExecutionSummary(true, waveCount, worldCount, duration);
    }

    private <T> void tickOwnedWorld(final T world, final Consumer<T> ticker) {
        final Object prev = this.currentTickingWorld.get();
        this.currentTickingWorld.set(world);
        try {
            ticker.accept(world);
        } finally {
            if (prev == null) {
                this.currentTickingWorld.remove();
            } else {
                this.currentTickingWorld.set(prev);
            }
        }
    }

    /**
     * Partitions a list of items into waves based on max parallelism.
     */
    public static <T> List<List<T>> partitionIntoWaves(final List<T> items, final int maxPerWave) {
        return partitionIntoWaves(items, maxPerWave, null);
    }

    /**
     * Partitions a list of items into waves based on max parallelism, avoiding co-scheduling
     * conflicting items.
     *
     * <p>The previous implementation performed plain sequential chunking despite documenting
     * itself as "conflict-free": worlds that interact heavily (portal-linked dimensions, worlds
     * with pending cross-world transfers) could land in the same wave and race each other's tick
     * threads. This version uses deterministic first-fit greedy assignment: an item is placed
     * into the earliest wave whose current members do not conflict with it and which is not yet
     * full; otherwise a new wave is opened. With a {@code null} predicate this degrades exactly
     * to the legacy sequential chunking.</p>
     *
     * @param conflicts Pairwise conflict predicate; {@code null} disables conflict avoidance
     */
    public static <T> List<List<T>> partitionIntoWaves(
        final List<T> items,
        final int maxPerWave,
        final java.util.function.BiPredicate<? super T, ? super T> conflicts
    ) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        final int batchSize = Math.max(1, maxPerWave);
        final int total = items.size();

        if (conflicts == null) {
            final int waveCount = (total + batchSize - 1) / batchSize;
            final List<List<T>> waves = new ArrayList<>(waveCount);
            for (int i = 0; i < total; i += batchSize) {
                final int end = Math.min(i + batchSize, total);
                waves.add(new ArrayList<>(items.subList(i, end)));
            }
            return waves;
        }

        // Deterministic first-fit greedy assignment. Worst case O(n^2 * waveSize), acceptable for
        // hundreds of active worlds evaluated once per tick.
        final List<List<T>> waves = new ArrayList<>();
        for (final T item : items) {
            boolean placed = false;
            for (final List<T> wave : waves) {
                if (wave.size() >= batchSize) {
                    continue;
                }
                boolean conflictsInWave = false;
                for (final T member : wave) {
                    if (conflicts.test(item, member)) {
                        conflictsInWave = true;
                        break;
                    }
                }
                if (!conflictsInWave) {
                    wave.add(item);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                final List<T> wave = new ArrayList<>(batchSize);
                wave.add(item);
                waves.add(wave);
            }
        }
        return waves;
    }

    /**
     * Checks if the current thread is ticking a world other than the target world.
     */
    public boolean shouldDeferCrossWorld(final Object targetWorld) {
        if (!this.parallelPhaseActive) {
            return false;
        }
        final Object current = this.currentTickingWorld.get();
        return current != null && current != targetWorld;
    }

    public boolean isParallelPhaseActive() {
        return this.parallelPhaseActive;
    }

    public Object getCurrentTickingWorld() {
        return this.currentTickingWorld.get();
    }

    public static int resolveOptimalWorkerCount() {
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(1, cores - 1);
    }

    public EngineMetrics metrics() {
        return new EngineMetrics(
            this.started.get(),
            this.configuredWorkers,
            this.parallelPhaseActive,
            this.parallelTicksExecuted.get(),
            this.sequentialTicksExecuted.get(),
            this.totalWavesExecuted.get(),
            this.totalWaveNanos.get(),
            this.parallelFailures.get()
        );
    }

    public record ExecutionSummary(
        boolean parallel,
        int waves,
        int worldsTicked,
        long durationNanos
    ) {
        public static final ExecutionSummary EMPTY = new ExecutionSummary(false, 0, 0, 0L);

        public double durationMillis() {
            return this.durationNanos / 1_000_000.0;
        }
    }

    public record EngineMetrics(
        boolean running,
        int workers,
        boolean inParallelPhase,
        long parallelTicks,
        long sequentialTicks,
        long totalWaves,
        long totalWaveNanos,
        long failures
    ) {
        public double averageWaveMillis() {
            if (this.totalWaves == 0) return 0.0;
            return (this.totalWaveNanos / 1_000_000.0) / (double) this.totalWaves;
        }
    }
}
