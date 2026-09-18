package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * AGC — High-Performance Parallel World Tick Engine (DAG Wavefront Architecture).
 *
 * <p>Provides multi-core parallel world ticking for 500+ active worlds on a single Paper instance.
 * Partitions worlds into conflict-free parallel execution waves using a dynamic dependency DAG
 * (Directed Acyclic Graph) and pairwise conflict detection. Dispatches waves across a pool of
 * dedicated worker threads plus the primary server thread, enforces barrier synchronization,
 * and coordinates post-barrier cross-world mutation draining.</p>
 *
 * <h2>What "parallel" means here (read this before quoting a metric)</h2>
 * <p>Worlds are split into waves by the pairwise conflict predicate the call site supplies. Two worlds
 * that conflict never share a wave, and a wave of one world is ticked inline on the calling thread —
 * so a tick can take the wave-dispatch branch and still execute every world serially. The default
 * overworld / nether / end are exactly that case: they belong to one Bukkit world and share its level
 * name, so the {@code MinecraftServer} predicate declares every pair conflicting and the partition is
 * three single-world waves. {@link EngineMetrics#parallelTicks()} alone therefore says nothing about
 * concurrency; {@link EngineMetrics#concurrentTicks()} and
 * {@link EngineMetrics#concurrentWaves()} do, and the dashboard reports both.</p>
 *
 * <p>Operator tuning lives in {@code config/agc.yml > performance}: {@code parallelWorldTick}
 * (via {@link AgcConfigSync} → {@link AgcCapabilityMatrix.Feature#PARALLEL_WORLD_TICK}),
 * {@code parallelWorldTickThreads} (pool size, applied live — no restart needed) and
 * {@code parallelWorldTickMinWorlds} (live-tunable floor below which worlds tick sequentially).</p>
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

    // Explicit inter-world dependency DAG
    private final ConcurrentHashMap<String, Set<String>> worldDependencies = new ConcurrentHashMap<>();

    //
    // NOTE on what these mean, because the difference is the whole point:
    //   parallelTicksExecuted   - how often the *wave-dispatch branch* ran. It does NOT imply that
    //                             two worlds ever ticked at the same time: a tick whose worlds all
    //                             conflict (for example the overworld/nether/end of one Bukkit world
    //                             share a level name) partitions into waves of exactly one world,
    //                             each of which is then ticked on the primary thread. Reporting that
    //                             as "parallel ticks" is the same class of lie the wiring audit exists
    //                             to prevent, so it is split into truthfully-named counters below.
    //   concurrentTicksExecuted - ticks in which at least one wave held 2+ worlds, i.e. real overlap.
    //   concurrentWavesExecuted - waves that were actually dispatched across worker threads.
    //   singleWorldWavesExecuted- waves of one world, run inline on the calling thread (no overlap).
    private final AtomicLong parallelTicksExecuted = new AtomicLong();
    private final AtomicLong sequentialTicksExecuted = new AtomicLong();
    private final AtomicLong totalWavesExecuted = new AtomicLong();
    private final AtomicLong totalWaveNanos = new AtomicLong();
    private final AtomicLong parallelFailures = new AtomicLong();
    private final AtomicLong concurrentTicksExecuted = new AtomicLong();
    private final AtomicLong concurrentWavesExecuted = new AtomicLong();
    private final AtomicLong singleWorldWavesExecuted = new AtomicLong();
    private final AtomicInteger peakWaveSize = new AtomicInteger();

    // Operator tuning (config/agc.yml > performance.parallelWorldTick*). Both the worker count and
    // the minimum-worlds floor are live-tunable via applyTuning; the pool is swapped, never fixed.
    private volatile int requestedWorkers = 0;
    private volatile int minWorldsFloor = 0;
    // parallelWorldTickForceUnsafe: drop the call site's conflict predicate. Strictly an operator
    // debugging knob; it lets portal-linked dimensions (same Bukkit level name) share a wave, which is
    // exactly what the default predicate forbids because those dimensions transfer entities to each
    // other. Warned about once per bootstrap, not per tick.
    private volatile boolean forceUnsafe = false;

    public static AgcParallelWorldTickEngine get() {
        return INSTANCE;
    }

    private AgcParallelWorldTickEngine() {}

    /**
     * Initializes the parallel world tick worker pool.
     *
     * <p>Idempotent: only the first call creates the pool. The size comes from an explicit
     * {@link #applyTuning} request when one arrived before bootstrap (i.e. from
     * {@code config/agc.yml} via {@link AgcConfigSync}), otherwise from the config file when it is
     * already loaded, otherwise from {@link #resolveOptimalWorkerCount()} (cores - 1).
     * Dispatch ({@link #executeWorldTicks}) null-guards the pool and runs sequentially until the
     * pool exists, so bootstrapping before config load is safe.</p>
     */
    public synchronized void bootstrap() {
        if (!this.started.compareAndSet(false, true)) {
            return;
        }
        final int workers = this.requestedWorkers > 0 ? clampWorkers(this.requestedWorkers) : configuredWorkerThreads();
        this.minWorldsFloor = configuredMinWorlds();
        this.configuredWorkers = workers;
        this.workerPool = newWorkerPool(workers);
        LOGGER.info("[AGC] Parallel World Tick Engine initialized with {} worker threads (DAG Wavefronts active, minWorlds={})",
            workers, Math.max(2, this.minWorldsFloor));
    }

    /**
     * Creates a fresh world-tick worker pool of the given size. Extracted so both
     * {@link #bootstrap()} and live resizes in {@link #applyTuning(int, int)} share one path.
     */
    private ExecutorService newWorkerPool(final int workers) {
        final ThreadFactory factory = r -> {
            final Thread t = new ca.spottedleaf.moonrise.common.util.TickThread(r, "AGC-WorldTick-Worker-" + this.workerId.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, throwable) ->
                LOGGER.error("Uncaught exception in AGC world tick worker {}", thread.getName(), throwable));
            return t;
        };
        return Executors.newFixedThreadPool(workers, factory);
    }

    /**
     * Applies operator tuning from {@code config/agc.yml > performance} (routed here by
     * {@link AgcConfigSync}). The minimum-worlds floor applies live to the very next tick. The
     * worker count also applies live: when it differs from the running pool a replacement pool is
     * built first, the volatile reference is swapped so new waves dispatch to it, and the old pool
     * is then drained orderly. In-flight waves hold their own {@link Future}s and run to
     * completion on the old pool, so no tick is lost and no restart is needed.
     *
     * @param workerThreads {@code parallelWorldTickThreads}; {@code <= 0} means auto (cores - 1,
     *                      keeps the bootstrap-sized pool)
     * @param minWorlds     {@code parallelWorldTickMinWorlds}; worlds below this stay sequential
     */
    public synchronized void applyTuning(final int workerThreads, final int minWorlds) {
        this.minWorldsFloor = Math.max(0, minWorlds);
        this.setForceUnsafe(this.readForceUnsafe());
        if (workerThreads <= 0) {
            return;
        }
        final int clamped = clampWorkers(workerThreads);
        if (!this.started.get() || this.workerPool == null) {
            this.requestedWorkers = clamped;
            if (!this.started.get()) {
                this.bootstrap();
            }
            return;
        }
        if (clamped == this.configuredWorkers) {
            return;
        }
        final ExecutorService previous = this.workerPool;
        this.workerPool = this.newWorkerPool(clamped);
        this.configuredWorkers = clamped;
        LOGGER.info("[AGC] parallel world tick pool resized to {} workers (config change applied live, no restart needed)",
            clamped);
        drainPool(previous);
    }

    /**
     * Orderly drains a replaced pool: previously submitted waves run to completion, queued tasks
     * are never dropped. Deliberately does not await termination while holding the engine monitor
     * (a worker running plugin code must never block a config thread); daemon worker threads exit
     * once the queue drains.
     */
    private static void drainPool(final ExecutorService pool) {
        if (pool == null) {
            return;
        }
        pool.shutdown();
    }

    /**
     * {@code parallelWorldTickThreads} from config, or auto (available processors - 1) when unset or
     * unavailable. The main thread ticks one world itself, so the pool is capped one below the core
     * count: total parallelism is {@code workers + 1}.
     */
    private static int configuredWorkerThreads() {
        int requested = 0;
        try {
            requested = io.papermc.paper.configuration.GlobalConfiguration.get().agc.performance.parallelWorldTickThreads;
        } catch (final Throwable ignored) {
            // Configuration is not initialised this early (unit tests, pre-config bootstrap): auto.
        }
        return requested > 0 ? clampWorkers(requested) : resolveOptimalWorkerCount();
    }

    /** {@code parallelWorldTickMinWorlds} from config, or 0 ("no extra floor") when unavailable. */
    private static int configuredMinWorlds() {
        try {
            return Math.max(0, io.papermc.paper.configuration.GlobalConfiguration.get().agc.performance.parallelWorldTickMinWorlds);
        } catch (final Throwable ignored) {
            return 0;
        }
    }

    private static int clampWorkers(final int requested) {
        final int max = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return Math.max(1, Math.min(requested, max));
    }

    private static boolean readForceUnsafe() {
        try {
            return io.papermc.paper.configuration.GlobalConfiguration.get().agc.performance.parallelWorldTickForceUnsafe;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /**
     * Sets {@code parallelWorldTickForceUnsafe}: when {@code true} the call site's conflict predicate
     * is ignored, so worlds that the server considers portal-linked (same Bukkit level name) may share
     * a wave. Only takes effect while {@link AgcCapabilityMatrix.Feature#PARALLEL_WORLD_TICK} is on.
     */
    public void setForceUnsafe(final boolean unsafe) {
        if (unsafe && !this.forceUnsafe) {
            LOGGER.warn("[AGC] parallelWorldTickForceUnsafe=true — world conflict detection is OFF: worlds"
                + " sharing a level name (overworld/nether/end of one Bukkit world) may now tick concurrently."
                + " Portal-linked entity transfer is NOT deferred for this case. Keep this off in production.");
        }
        this.forceUnsafe = unsafe;
    }

    public boolean isForceUnsafe() {
        return this.forceUnsafe;
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
     * Registers a directed execution dependency between two worlds.
     * World B will be scheduled in a strictly subsequent wave after World A.
     */
    public void addWorldDependency(final String worldA, final String worldB) {
        if (worldA != null && worldB != null && !worldA.equalsIgnoreCase(worldB)) {
            this.worldDependencies.computeIfAbsent(worldA, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(worldB);
        }
    }

    /**
     * Removes an execution dependency.
     */
    public void removeWorldDependency(final String worldA, final String worldB) {
        if (worldA != null && worldB != null) {
            final Set<String> deps = this.worldDependencies.get(worldA);
            if (deps != null) {
                deps.remove(worldB);
            }
        }
    }

    public void clearDependencies() {
        this.worldDependencies.clear();
    }

    /**
     * Ticks a collection of worlds using parallel waves or sequential fallback.
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
     * @param conflicts     Optional pairwise predicate; worlds that "conflict" are never scheduled
     *                      into the same wave. {@code null} disables conflict avoidance.
     * @param <T>           World context type
     * @return ExecutionSummary containing timing and wave statistics
     */
    public <T> ExecutionSummary executeWorldTicks(
        final List<T> worlds,
        final Consumer<T> ticker,
        final int minWorlds,
        final BiPredicate<? super T, ? super T> conflicts
    ) {
        if (worlds == null || worlds.isEmpty()) {
            return ExecutionSummary.EMPTY;
        }

        final int worldCount = worlds.size();
        // The call-site floor and the operator's parallelWorldTickMinWorlds both apply; the higher wins,
        // and 2 is the hard floor (wave dispatch for a single world is pure overhead).
        final int effectiveMinWorlds = Math.max(2, Math.max(minWorlds, this.minWorldsFloor));
        final BiPredicate<? super T, ? super T> effectiveConflicts = this.forceUnsafe ? null : conflicts;
        final boolean allowParallel = AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_WORLD_TICK)
            && this.started.get()
            && this.workerPool != null
            && worldCount >= effectiveMinWorlds;

        if (!allowParallel) {
            // Sequential execution path
            final long start = System.nanoTime();
            for (final T world : worlds) {
                ticker.accept(world);
            }
            final long duration = System.nanoTime() - start;
            this.sequentialTicksExecuted.incrementAndGet();
            return new ExecutionSummary(false, false, 1, worldCount, duration);
        }

        // Parallel wave execution path
        final long start = System.nanoTime();
        this.parallelPhaseActive = true;
        int waveCount = 0;
        boolean overlapped = false;
        Throwable tickFailure = null;

        try {
            final List<List<T>> waves = partitionIntoWaves(
                worlds,
                this.configuredWorkers + 1,
                effectiveConflicts,
                this.worldDependencies,
                AgcParallelWorldTickEngine::extractWorldName
            );
            waveCount = waves.size();

            for (final List<T> wave : waves) {
                final long waveStart = System.nanoTime();
                final int waveSize = wave.size();
                if (waveSize == 1) {
                    // Single world in wave: tick on primary thread directly. A tick whose every wave has
                    // one world is *serialized*: it takes the dispatch branch but never overlaps two
                    // worlds. Recorded separately so the counters cannot advertise concurrency that did
                    // not happen (e.g. the overworld/nether/end of one Bukkit world all share a level
                    // name and therefore always conflict).
                    this.singleWorldWavesExecuted.incrementAndGet();
                    tickOwnedWorld(wave.get(0), ticker);
                } else {
                    this.concurrentWavesExecuted.incrementAndGet();
                    this.peakWaveSize.accumulateAndGet(waveSize, Math::max);
                    overlapped = true;
                    // Multi-world wave: submit 1..waveSize-1 to worker pool, primary thread ticks wave[0]
                    final List<Future<?>> futures = new ArrayList<>(waveSize - 1);
                    for (int i = 1; i < waveSize; i++) {
                        final T world = wave.get(i);
                        futures.add(this.workerPool.submit(() -> {
                            tickOwnedWorld(world, ticker);
                            return null;
                        }));
                    }

                    // Primary thread ticks wave[0] concurrently
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
                            futures.forEach(fut -> fut.cancel(true));
                            break;
                        }
                    }
                }
                this.totalWaveNanos.addAndGet(System.nanoTime() - waveStart);
                this.totalWavesExecuted.incrementAndGet();
            }
        } finally {
            this.parallelPhaseActive = false;
        }

        final long duration = System.nanoTime() - start;
        this.parallelTicksExecuted.incrementAndGet();
        if (overlapped) {
            this.concurrentTicksExecuted.incrementAndGet();
        }

        if (tickFailure != null) {
            this.parallelFailures.incrementAndGet();
            LOGGER.error("Error occurred during parallel world tick wave", tickFailure);
            if (tickFailure instanceof RuntimeException re) throw re;
            if (tickFailure instanceof Error err) throw err;
            throw new RuntimeException(tickFailure);
        }

        return new ExecutionSummary(true, overlapped, waveCount, worldCount, duration);
    }

    public static String extractWorldName(final Object world) {
        if (world == null) {
            return "world";
        }
        if (world instanceof String s) {
            return s;
        }
        if (world instanceof net.minecraft.world.level.Level level) {
            return ca.spottedleaf.moonrise.common.util.WorldUtil.getWorldName(level);
        }
        if (world instanceof org.bukkit.World bw) {
            return bw.getName();
        }
        return world.toString();
    }

    private <T> void tickOwnedWorld(final T world, final Consumer<T> ticker) {
        final Object prev = this.currentTickingWorld.get();
        this.currentTickingWorld.set(world);
        final String worldId = extractWorldName(world);
        try (final AgcPluginVirtualizer.ContextScope ignored = AgcPluginVirtualizer.get().enterContext(worldId, 0L)) {
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
     * Partitions items into waves based on max parallelism and conflict constraints.
     */
    public static <T> List<List<T>> partitionIntoWaves(final List<T> items, final int maxPerWave) {
        return partitionIntoWaves(items, maxPerWave, null, Collections.emptyMap(), AgcParallelWorldTickEngine::extractWorldName);
    }

    /**
     * Partitions items into waves using a greedy conflict-free wavefront assignment.
     */
    public static <T> List<List<T>> partitionIntoWaves(
        final List<T> items,
        final int maxPerWave,
        final BiPredicate<? super T, ? super T> conflicts
    ) {
        return partitionIntoWaves(items, maxPerWave, conflicts, Collections.emptyMap(), AgcParallelWorldTickEngine::extractWorldName);
    }

    /**
     * Partitions items into waves using a DAG-aware wavefront leveling partitioner with conflict constraints.
     */
    public static <T> List<List<T>> partitionIntoWaves(
        final List<T> items,
        final int maxPerWave,
        final BiPredicate<? super T, ? super T> conflicts,
        final Map<String, Set<String>> dependencies
    ) {
        return partitionIntoWaves(items, maxPerWave, conflicts, dependencies, AgcParallelWorldTickEngine::extractWorldName);
    }

    /**
     * Partitions items into waves using a full DAG-aware wavefront leveling partitioner with conflict constraints
     * and explicit world name extraction.
     */
    public static <T> List<List<T>> partitionIntoWaves(
        final List<T> items,
        final int maxPerWave,
        final BiPredicate<? super T, ? super T> conflicts,
        final Map<String, Set<String>> dependencies,
        final Function<? super T, String> nameExtractor
    ) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        final int batchSize = Math.max(1, maxPerWave);
        final int total = items.size();

        if (total == 1) {
            final List<List<T>> single = new ArrayList<>(1);
            single.add(new ArrayList<>(items));
            return single;
        }

        final boolean hasDeps = dependencies != null && !dependencies.isEmpty();

        // Fast path: if no dependencies and no conflicts, use flat chunking
        if (!hasDeps && conflicts == null) {
            final int waveCount = (total + batchSize - 1) / batchSize;
            final List<List<T>> waves = new ArrayList<>(waveCount);
            for (int i = 0; i < total; i += batchSize) {
                final int end = Math.min(i + batchSize, total);
                waves.add(new ArrayList<>(items.subList(i, end)));
            }
            return waves;
        }

        // Fast path: conflicts only (no dependencies) -> preserved original greedy assignment
        if (!hasDeps) {
            final List<List<T>> waves = new ArrayList<>();
            for (final T item : items) {
                boolean placed = false;
                for (final List<T> wave : waves) {
                    if (wave.size() >= batchSize) {
                        continue;
                    }
                    boolean conflictsInWave = false;
                    for (final T member : wave) {
                        if (conflicts.test(item, member) || conflicts.test(member, item)) {
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

        // Full DAG Wavefront Partitioner
        final Function<? super T, String> namer = nameExtractor != null ? nameExtractor : AgcParallelWorldTickEngine::extractWorldName;
        final Map<T, String> itemToName = new HashMap<>(total);
        final Map<String, List<T>> nameToItems = new HashMap<>(total);

        for (final T item : items) {
            final String rawName = namer.apply(item);
            final String name = rawName != null ? rawName.toLowerCase(Locale.ROOT) : "";
            itemToName.put(item, name);
            nameToItems.computeIfAbsent(name, k -> new ArrayList<>(1)).add(item);
        }

        // Build directed adjacency
        final Map<T, Set<T>> prerequisites = new HashMap<>(total);
        final Map<T, Set<T>> successors = new HashMap<>(total);
        final Map<T, Integer> inDegrees = new HashMap<>(total);

        for (final T item : items) {
            prerequisites.put(item, new HashSet<>());
            successors.put(item, new HashSet<>());
            inDegrees.put(item, 0);
        }

        for (final Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            final String aName = entry.getKey().toLowerCase(Locale.ROOT);
            final List<T> aList = nameToItems.get(aName);
            if (aList == null || aList.isEmpty()) {
                continue;
            }
            for (final String bRaw : entry.getValue()) {
                final String bName = bRaw.toLowerCase(Locale.ROOT);
                final List<T> bList = nameToItems.get(bName);
                if (bList == null || bList.isEmpty()) {
                    continue;
                }
                for (final T a : aList) {
                    for (final T b : bList) {
                        if (a != b && prerequisites.get(b).add(a)) {
                            successors.get(a).add(b);
                            inDegrees.put(b, inDegrees.get(b) + 1);
                        }
                    }
                }
            }
        }

        // Kahn's algorithm for topological ordering
        final ArrayDeque<T> readyQueue = new ArrayDeque<>(total);
        for (final T item : items) {
            if (inDegrees.get(item) == 0) {
                readyQueue.add(item);
            }
        }

        final List<T> ordered = new ArrayList<>(total);
        while (!readyQueue.isEmpty()) {
            final T curr = readyQueue.poll();
            ordered.add(curr);
            for (final T succ : successors.get(curr)) {
                final int newDeg = inDegrees.get(succ) - 1;
                inDegrees.put(succ, newDeg);
                if (newDeg == 0) {
                    readyQueue.add(succ);
                }
            }
        }

        // Cycle fallback: append any remaining items
        if (ordered.size() < total) {
            for (final T item : items) {
                if (!ordered.contains(item)) {
                    ordered.add(item);
                }
            }
        }

        // Greedy Wavefront Leveling
        final List<List<T>> waves = new ArrayList<>();
        final Map<T, Integer> itemWave = new HashMap<>(total);

        for (final T item : ordered) {
            int minWave = 0;
            for (final T prereq : prerequisites.get(item)) {
                final Integer pWave = itemWave.get(prereq);
                if (pWave != null) {
                    minWave = Math.max(minWave, pWave + 1);
                }
            }

            int targetWave = minWave;
            boolean placed = false;

            while (targetWave < waves.size()) {
                final List<T> wave = waves.get(targetWave);
                if (wave.size() < batchSize) {
                    boolean conflictsInWave = false;
                    for (final T member : wave) {
                        if (conflicts != null && (conflicts.test(item, member) || conflicts.test(member, item))) {
                            conflictsInWave = true;
                            break;
                        }
                        if (prerequisites.get(item).contains(member) || successors.get(item).contains(member)) {
                            conflictsInWave = true;
                            break;
                        }
                    }
                    if (!conflictsInWave) {
                        wave.add(item);
                        itemWave.put(item, targetWave);
                        placed = true;
                        break;
                    }
                }
                targetWave++;
            }

            if (!placed) {
                while (waves.size() <= targetWave) {
                    waves.add(new ArrayList<>(batchSize));
                }
                waves.get(targetWave).add(item);
                itemWave.put(item, targetWave);
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
        if (current == null || targetWorld == null) {
            return false;
        }
        if (current == targetWorld) {
            return false;
        }
        final String currentName = extractWorldName(current);
        final String targetName = extractWorldName(targetWorld);
        return !currentName.equalsIgnoreCase(targetName);
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
            this.parallelFailures.get(),
            this.concurrentTicksExecuted.get(),
            this.concurrentWavesExecuted.get(),
            this.singleWorldWavesExecuted.get(),
            this.peakWaveSize.get(),
            Math.max(2, this.minWorldsFloor)
        );
    }

    /** Test/telemetry hook: forgets accumulated wave counters (pool and tuning are untouched). */
    public void resetMetrics() {
        this.parallelTicksExecuted.set(0L);
        this.sequentialTicksExecuted.set(0L);
        this.totalWavesExecuted.set(0L);
        this.totalWaveNanos.set(0L);
        this.parallelFailures.set(0L);
        this.concurrentTicksExecuted.set(0L);
        this.concurrentWavesExecuted.set(0L);
        this.singleWorldWavesExecuted.set(0L);
        this.peakWaveSize.set(0);
    }

    /**
     * @param parallel      the wave-dispatch branch was taken
     * @param concurrent    at least one wave held 2+ worlds, i.e. two worlds really ticked at once.
     *                      {@code parallel && !concurrent} means every world was partitioned into its
     *                      own wave (all pairs conflicted) and the tick ran serialized on one thread.
     * @param waves         number of waves the tick was partitioned into
     * @param worldsTicked  worlds passed to the ticker
     * @param durationNanos wall time of the whole dispatch
     */
    public record ExecutionSummary(
        boolean parallel,
        boolean concurrent,
        int waves,
        int worldsTicked,
        long durationNanos
    ) {
        public static final ExecutionSummary EMPTY = new ExecutionSummary(false, false, 0, 0, 0L);

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
        long failures,
        long concurrentTicks,
        long concurrentWaves,
        long singleWorldWaves,
        int peakWaveSize,
        int minWorlds
    ) {
        public double averageWaveMillis() {
            if (this.totalWaves == 0) return 0.0;
            return (this.totalWaveNanos / 1_000_000.0) / (double) this.totalWaves;
        }

        /**
         * Share of dispatch-branch ticks that actually overlapped two or more worlds. {@code 0.0} with
         * {@code parallelTicks > 0} means every tick was serialized by the conflict rules (for example
         * the overworld/nether/end of a single Bukkit world, which share a level name, or fewer worlds
         * than {@code minWorlds}).
         */
        public double concurrencyRatio() {
            if (this.parallelTicks == 0) return 0.0;
            return (double) this.concurrentTicks / (double) this.parallelTicks;
        }
    }
}
