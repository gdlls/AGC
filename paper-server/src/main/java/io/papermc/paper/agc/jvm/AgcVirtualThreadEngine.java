package io.papermc.paper.agc.jvm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Project Loom Virtual Threads Engine (Java 21+ Gale / Minestom port).
 *
 * <p>Provides lightweight virtual-thread executors for blocking I/O tasks:
 * <ul>
 *   <li>Async chunk serialization and region file storage writes</li>
 *   <li>Session authentication and HTTP Mojang API requests</li>
 *   <li>Async plugin task executions</li>
 * </ul>
 * Replaces OS thread context switches with M:N lightweight virtual carrier scheduling.</p>
 */
public final class AgcVirtualThreadEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcVirtualThreadEngine.class);
    private static final AgcVirtualThreadEngine INSTANCE = new AgcVirtualThreadEngine();

    private final boolean virtualThreadsSupported;
    private final ExecutorService ioExecutor;
    private final AtomicLong tasksDispatched = new AtomicLong();

    public static AgcVirtualThreadEngine get() {
        return INSTANCE;
    }

    private AgcVirtualThreadEngine() {
        boolean supported = false;
        ExecutorService exec = null;
        try {
            final ThreadFactory factory = Thread.ofVirtual().name("AGC-VirtualIO-", 1).factory();
            exec = Executors.newThreadPerTaskExecutor(factory);
            supported = true;
            LOGGER.info("[AGC] Project Loom Virtual Threads Engine enabled for asynchronous I/O");
        } catch (final Throwable t) {
            LOGGER.info("[AGC] Project Loom Virtual Threads unavailable, using standard cached thread pool");
            exec = Executors.newCachedThreadPool();
            supported = false;
        }
        this.virtualThreadsSupported = supported;
        this.ioExecutor = exec;
    }

    public boolean isVirtualThreadsSupported() {
        return this.virtualThreadsSupported;
    }

    public ExecutorService getIoExecutor() {
        return this.ioExecutor;
    }

    public void executeIo(final Runnable task) {
        this.tasksDispatched.incrementAndGet();
        this.ioExecutor.execute(task);
    }

    public long getTasksDispatched() {
        return this.tasksDispatched.get();
    }
}
