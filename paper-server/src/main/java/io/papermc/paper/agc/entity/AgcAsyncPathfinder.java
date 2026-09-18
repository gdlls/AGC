package io.papermc.paper.agc.entity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
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
 * AGC — Asynchronous Pathfinding Engine (Leaf MC / Gale port).
 *
 * <p>Offloads heavy A* path calculations from the primary server thread to a dedicated
 * background worker pool, preventing tick rate drops when dozens of mobs pathfind simultaneously.</p>
 */
public final class AgcAsyncPathfinder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcAsyncPathfinder.class);
    private static final AgcAsyncPathfinder INSTANCE = new AgcAsyncPathfinder();

    private final ExecutorService workerPool;
    private final AtomicLong asyncTasksSubmitted = new AtomicLong();
    private final AtomicLong asyncTasksCompleted = new AtomicLong();
    private final AtomicLong asyncFallbacks = new AtomicLong();

    public static AgcAsyncPathfinder get() {
        return INSTANCE;
    }

    private AgcAsyncPathfinder() {
        final int threads = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        final AtomicInteger counter = new AtomicInteger(1);
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "AGC-AsyncPathfinder-" + counter.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        this.workerPool = Executors.newFixedThreadPool(threads, factory);
        LOGGER.info("[AGC] Asynchronous Pathfinding Engine initialized with {} worker threads", threads);
    }

    /**
     * Submits an asynchronous path calculation supplier.
     */
    public CompletableFuture<Path> submitPathCalculation(final Supplier<Path> pathSupplier) {
        this.asyncTasksSubmitted.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                final Path path = pathSupplier.get();
                this.asyncTasksCompleted.incrementAndGet();
                return path;
            } catch (final Throwable t) {
                this.asyncFallbacks.incrementAndGet();
                return null;
            }
        }, this.workerPool);
    }

    public long getAsyncTasksSubmitted() {
        return this.asyncTasksSubmitted.get();
    }

    public long getAsyncTasksCompleted() {
        return this.asyncTasksCompleted.get();
    }

    public long getAsyncFallbacks() {
        return this.asyncFallbacks.get();
    }
}
