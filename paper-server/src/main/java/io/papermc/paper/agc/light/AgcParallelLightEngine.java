package io.papermc.paper.agc.light;

import io.papermc.paper.agc.AgcCapabilityMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * AGC — ScalableLux Port: parallel light-task splitter + StarLight coalescing bridge.
 *
 * <p>Starlight's stateless propagator allows parallel light updates when scheduling
 * is right (the core ScalableLux insight, already proven on Fabric). This engine:</p>
 * <ul>
 *   <li>coalesces per-section light requests through
 *       {@link AgcStarLightBatchOptimizer} (dedup within the tick), then</li>
 *   <li>splits the unique section set into cache-friendly contiguous ranges sized
 *       by core count, and</li>
 *   <li>dispatches each range to the light worker pool, joining before the chunk
 *       pipeline proceeds (ordering preserved by the join barrier).</li>
 * </ul>
 *
 * <p>Parity: light values computed are identical — only <i>which thread</i> runs
 * the propagation changes, and the barrier guarantees visibility before reads.
 * Gated by {@code PARALLEL_LIGHT_ENGINE} (AGGRESSIVE opt-in); baseline servers
 * keep the vanilla single-threaded light path untouched.</p>
 */
public final class AgcParallelLightEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcParallelLightEngine.class);
    private static final AgcParallelLightEngine INSTANCE = new AgcParallelLightEngine();

    private volatile ExecutorService workers;
    private volatile int workerCount;

    private final AtomicLong batchesDispatched = new AtomicLong();
    private final AtomicLong sectionsProcessed = new AtomicLong();
    private final AtomicLong coalescedSkips = new AtomicLong();
    private final AtomicLong barrierWaits = new AtomicLong();

    public static AgcParallelLightEngine get() {
        return INSTANCE;
    }

    private AgcParallelLightEngine() {}

    public synchronized void bootstrap() {
        if (this.workers != null && !this.workers.isShutdown()) {
            return;
        }
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        // Reserve headroom: main thread + Netty + chunk workers + GC keep priority.
        // Low-spec machines (<=16 cores) keep the proven conservative sizing; high-core
        // machines scale up so light propagation stops being single-digit-thread bound
        // while 30+ cores sit idle (light work is CPU-bound but embarrassingly parallel
        // across sections, joined by the barrier before the chunk pipeline proceeds).
        this.workerCount = cores <= 16
            ? Math.max(1, Math.min(8, cores / 4))
            : Math.max(4, Math.min(16, cores / 2));
        final AtomicInteger index = new AtomicInteger();
        final ThreadFactory factory = runnable -> {
            final Thread thread = new Thread(runnable, "AGC-Light-" + index.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        };
        this.workers = Executors.newFixedThreadPool(this.workerCount, factory);
        LOGGER.info("AGC parallel light engine bootstrapped with {} workers ({} cores)", this.workerCount, cores);
    }

    public synchronized void shutdown() {
        final ExecutorService pool = this.workers;
        this.workers = null;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
    }

    public boolean isAvailable() {
        final ExecutorService pool = this.workers;
        return pool != null && !pool.isShutdown()
            && AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_LIGHT_ENGINE);
    }

    public Future<?> submitTask(final Runnable task) {
        if (task == null) {
            return CompletableFuture.completedFuture(null);
        }
        final ExecutorService pool = this.workers;
        if (pool == null || pool.isShutdown()
            || !AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_LIGHT_ENGINE)) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }
        this.batchesDispatched.incrementAndGet();
        return pool.submit(task);
    }

    /**
     * Plans contiguous section ranges for {@code totalSections} across workers.
     * Pure function — unit testable without threads.
     */
    public List<int[]> planRanges(final int totalSections, final int parallelism) {
        final List<int[]> ranges = new ArrayList<>();
        if (totalSections <= 0) {
            return ranges;
        }
        final int lanes = Math.max(1, Math.min(parallelism <= 0 ? 1 : parallelism, totalSections));
        final int base = totalSections / lanes;
        int remainder = totalSections % lanes;
        int start = 0;
        for (int i = 0; i < lanes; i++) {
            final int length = base + (remainder-- > 0 ? 1 : 0);
            ranges.add(new int[] {start, start + length});
            start += length;
        }
        return ranges;
    }

    /**
     * Runs {@code sectionTask} for {@code [0, totalSections)} across the pool and
     * joins. Falls back to inline execution when the pool is unavailable.
     *
     * @return number of sections processed
     */
    public int dispatchSections(final int totalSections, final IntConsumer sectionTask) throws InterruptedException {
        if (totalSections <= 0 || sectionTask == null) {
            return 0;
        }
        final ExecutorService pool = this.workers;
        if (pool == null || pool.isShutdown()
            || !AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_LIGHT_ENGINE)) {
            for (int i = 0; i < totalSections; i++) {
                sectionTask.accept(i);
            }
            this.sectionsProcessed.addAndGet(totalSections);
            return totalSections;
        }
        final List<int[]> ranges = this.planRanges(totalSections, this.workerCount);
        final List<Future<?>> futures = new ArrayList<>(ranges.size());
        this.batchesDispatched.incrementAndGet();
        for (final int[] range : ranges) {
            futures.add(pool.submit(() -> {
                for (int i = range[0]; i < range[1]; i++) {
                    sectionTask.accept(i);
                }
            }));
        }
        for (final Future<?> future : futures) {
            this.barrierWaits.incrementAndGet();
            try {
                future.get();
            } catch (final java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("AGC parallel light task failed", e.getCause() != null ? e.getCause() : e);
            }
        }
        this.sectionsProcessed.addAndGet(totalSections);
        return totalSections;
    }

    /**
     * Coalescing bridge: queues a section update, then drains the unique set and
     * processes it in parallel. Returns processed unique-section count.
     */
    public int coalesceAndDispatch(
        final int chunkX, final int sectionY, final int chunkZ,
        final boolean blockLight, final boolean skyLight,
        final java.util.function.Consumer<AgcStarLightBatchOptimizer.LightSectionUpdate> consumer
    ) throws InterruptedException {
        final AgcStarLightBatchOptimizer batcher = AgcStarLightBatchOptimizer.get();
        if (batcher.queueCoalescedSectionUpdate(chunkX, sectionY, chunkZ, blockLight, skyLight)) {
            this.coalescedSkips.incrementAndGet();
        }
        final List<AgcStarLightBatchOptimizer.LightSectionUpdate> drained = new ArrayList<>();
        batcher.drainPendingUpdates(drained::add);
        if (drained.isEmpty()) {
            return 0;
        }
        final AtomicInteger processed = new AtomicInteger();
        this.dispatchSections(drained.size(), index -> {
            if (consumer != null) {
                consumer.accept(drained.get(index));
            }
            processed.incrementAndGet();
        });
        return processed.get();
    }

    public CompletableFuture<Integer> dispatchSectionsAsync(final int totalSections, final IntConsumer sectionTask) {
        final ExecutorService pool = this.workers;
        if (pool == null || pool.isShutdown()) {
            return CompletableFuture.completedFuture(0);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.dispatchSections(totalSections, sectionTask);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }, pool);
    }

    public void clearMetrics() {
        this.batchesDispatched.set(0);
        this.sectionsProcessed.set(0);
        this.coalescedSkips.set(0);
        this.barrierWaits.set(0);
    }

    public LightMetrics metrics() {
        return new LightMetrics(
            this.workerCount,
            this.batchesDispatched.get(),
            this.sectionsProcessed.get(),
            this.coalescedSkips.get(),
            this.barrierWaits.get()
        );
    }

    public record LightMetrics(
        int workerCount,
        long batchesDispatched,
        long sectionsProcessed,
        long coalescedSkips,
        long barrierWaits
    ) {}
}
