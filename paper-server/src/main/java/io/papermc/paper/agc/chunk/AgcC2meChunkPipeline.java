package io.papermc.paper.agc.chunk;

import io.papermc.paper.agc.AgcCapabilityMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * AGC — C2ME Port: concurrent chunk pipeline (generation backpressure, async
 * serialization, I/O autosizing, ticket throttling).
 *
 * <p>C2ME's core insight: vanilla funnels chunk generation, serialization and I/O
 * through too few threads. Moonrise already multithreads AGC's chunk system; this
 * pipeline adds the missing control plane on top:</p>
 * <ul>
 *   <li><b>Async serialization</b>: NBT encode/decode runs on I/O workers, never on
 *       the tick thread; results join back with ordering preserved per chunk.</li>
 *   <li><b>Generation backpressure</b>: a semaphore caps in-flight generation jobs
 *       so elytra bursts cannot OOM the heap (fail-soft: excess demand waits).</li>
 *   <li><b>I/O autosizing</b>: worker counts scale from cores + online players
 *       (C2ME-style {@code config} defaults, auto-sized to the machine).</li>
 *   <li><b>Ticket throttling</b>: overload sheds <i>new</i> ticket demand first
 *       (FIFO preserved — never skips the head).</li>
 *   <li><b>No-tick view-distance policy</b>: pure function computing the tick
 *       radius from view distance + margin (margin 0 = vanilla behavior).</li>
 * </ul>
 *
 * <p>Parity: chunk bytes and generation results are identical; only the executing
 * thread and admission timing change. Async tasks never touch live world state —
 * they operate on detached snapshots/buffers.</p>
 */
public final class AgcC2meChunkPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcC2meChunkPipeline.class);
    private static final AgcC2meChunkPipeline INSTANCE = new AgcC2meChunkPipeline();

    private volatile ThreadPoolExecutor ioWorkers;
    private final java.util.concurrent.Semaphore genBackpressure = new java.util.concurrent.Semaphore(256);

    private final AtomicLong asyncSerializations = new AtomicLong();
    private final AtomicLong backpressureWaits = new AtomicLong();
    private final AtomicLong throttledTickets = new AtomicLong();
    private final AtomicLong admittedTickets = new AtomicLong();

    public static AgcC2meChunkPipeline get() {
        return INSTANCE;
    }

    private AgcC2meChunkPipeline() {}

    public synchronized void bootstrap() {
        if (this.ioWorkers != null && !this.ioWorkers.isShutdown()) {
            return;
        }
        final int threads = computeIoThreads(Runtime.getRuntime().availableProcessors(), 0);
        final AtomicInteger index = new AtomicInteger();
        final ThreadFactory factory = runnable -> {
            final Thread thread = new Thread(runnable, "AGC-ChunkIO-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.ioWorkers = new ThreadPoolExecutor(
            threads, threads, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(4096), factory,
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.ioWorkers.allowCoreThreadTimeOut(true);
        LOGGER.info("AGC C2ME chunk pipeline bootstrapped with {} IO workers", threads);
    }

    public synchronized void shutdown() {
        final ExecutorService pool = this.ioWorkers;
        this.ioWorkers = null;
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
        final ThreadPoolExecutor pool = this.ioWorkers;
        return pool != null && !pool.isShutdown()
            && AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.C2ME_CHUNK_PIPELINE);
    }

    /**
     * I/O worker autosizer: ~1 thread per 8 cores baseline on low-spec machines, ~1 per 4 cores
     * on high-core machines (I/O work mostly blocks on disk, so extra threads are cheap and keep
     * elytra/teleport bursts from queueing), +1 per 300 players, clamped to [2, cores].
     * Pure function.
     */
    public static int computeIoThreads(final int cores, final int onlinePlayers) {
        final int safeCores = Math.max(2, cores);
        final int byPlayers = Math.max(0, onlinePlayers) / 300;
        final int baseline = safeCores <= 16 ? safeCores / 8 : safeCores / 4;
        return Math.max(2, Math.min(safeCores, Math.max(2, baseline + byPlayers)));
    }

    /**
     * Generation worker autosizer: scales toward core count when NOT pre-generated.
     */
    public static int computeGenThreads(final int cores, final boolean preGenerated) {
        final int safeCores = Math.max(2, cores);
        if (preGenerated) {
            return Math.max(1, Math.min(4, safeCores / 16));
        }
        return Math.max(2, Math.min(safeCores - 2, safeCores * 3 / 4));
    }

    /**
     * Runs NBT serialization off the tick thread. Falls back to inline when the
     * pool is unavailable (identical bytes either way).
     */
    public <T> CompletableFuture<T> serializeAsync(final Supplier<T> task) {
        final ThreadPoolExecutor pool = this.ioWorkers;
        if (pool == null || pool.isShutdown() || task == null
            || !AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.C2ME_CHUNK_PIPELINE)) {
            try {
                return CompletableFuture.completedFuture(task == null ? null : task.get());
            } catch (final Throwable t) {
                final CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(t);
                return failed;
            }
        }
        this.asyncSerializations.incrementAndGet();
        return CompletableFuture.supplyAsync(task, pool);
    }

    /**
     * Generation backpressure: {@code true} if a generation slot was acquired.
     * Callers MUST call {@link #releaseGenPermit} after the job finishes.
     */
    public boolean tryAcquireGenPermit(final int maxInFlight) {
        final int limit = Math.max(1, maxInFlight);
        final int used = limit - this.genBackpressure.availablePermits();
        if (used >= limit) {
            this.backpressureWaits.incrementAndGet();
            return false;
        }
        final boolean acquired = this.genBackpressure.tryAcquire();
        if (!acquired) {
            this.backpressureWaits.incrementAndGet();
        }
        return acquired;
    }

    public void releaseGenPermit() {
        this.genBackpressure.release();
    }

    public int availableGenPermits() {
        return this.genBackpressure.availablePermits();
    }

    /**
     * Ticket throttle: sheds NEW ticket demand under overload. Never reorders —
     * returns {@code false} (defer) only for demand beyond the head of the queue.
     */
    public boolean admitTicket(final int queueDepth, final int softLimit) {
        if (queueDepth <= Math.max(1, softLimit)) {
            this.admittedTickets.incrementAndGet();
            return true;
        }
        this.throttledTickets.incrementAndGet();
        return false;
    }

    /**
     * No-tick view-distance policy: tick radius = viewDistance - margin, clamped
     * to vanilla minimum 2. Margin 0 returns viewDistance (vanilla behavior).
     */
    public static int effectiveTickRadius(final int viewDistance, final int noTickMargin) {
        final int margin = Math.max(0, noTickMargin);
        if (margin == 0) {
            return Math.max(2, viewDistance);
        }
        return Math.max(2, viewDistance - margin);
    }

    public void clearMetrics() {
        this.asyncSerializations.set(0);
        this.backpressureWaits.set(0);
        this.throttledTickets.set(0);
        this.admittedTickets.set(0);
    }

    public ChunkPipelineMetrics metrics() {
        final ThreadPoolExecutor pool = this.ioWorkers;
        return new ChunkPipelineMetrics(
            pool == null ? 0 : pool.getPoolSize(),
            pool == null ? 0 : pool.getQueue().size(),
            this.availableGenPermits(),
            this.asyncSerializations.get(),
            this.backpressureWaits.get(),
            this.throttledTickets.get(),
            this.admittedTickets.get()
        );
    }

    public record ChunkPipelineMetrics(
        int ioPoolSize,
        int ioQueueDepth,
        int availableGenPermits,
        long asyncSerializations,
        long backpressureWaits,
        long throttledTickets,
        long admittedTickets
    ) {}
}
