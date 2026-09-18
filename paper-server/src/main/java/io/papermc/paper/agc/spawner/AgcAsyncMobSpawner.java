package io.papermc.paper.agc.spawner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * AGC — Asynchronous Mob Spawner (Pufferfish / Purpur port).
 *
 * <p>Offloads candidate block sampling, biome tag validation, and light checking to
 * background worker threads, committing only valid entity additions to the main world tick.</p>
 */
public final class AgcAsyncMobSpawner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcAsyncMobSpawner.class);
    private static final AgcAsyncMobSpawner INSTANCE = new AgcAsyncMobSpawner();

    private final ExecutorService workerPool;
    private final AtomicLong asyncSpawnTasks = new AtomicLong();
    private final AtomicLong asyncSpawnSuccesses = new AtomicLong();

    public static AgcAsyncMobSpawner get() {
        return INSTANCE;
    }

    private AgcAsyncMobSpawner() {
        final int threads = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 4));
        final AtomicInteger counter = new AtomicInteger(1);
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "AGC-AsyncMobSpawner-" + counter.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        this.workerPool = Executors.newFixedThreadPool(threads, factory);
        LOGGER.info("[AGC] Asynchronous Mob Spawning Engine initialized with {} workers", threads);
    }

    /**
     * Submits an asynchronous mob spawn candidate check.
     */
    public <T> CompletableFuture<T> submitAsyncSpawnEvaluation(final Supplier<T> evaluationSupplier) {
        this.asyncSpawnTasks.incrementAndGet();
        return CompletableFuture.supplyAsync(evaluationSupplier, this.workerPool);
    }

    public long getAsyncSpawnTasks() {
        return this.asyncSpawnTasks.get();
    }

    public long getAsyncSpawnSuccesses() {
        return this.asyncSpawnSuccesses.get();
    }
}
