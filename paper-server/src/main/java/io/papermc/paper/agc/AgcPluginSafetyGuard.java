package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * AGC — Plugin Thread-Safety Guard & Primary-Thread Bridge.
 *
 * <p>Legacy Bukkit/Paper plugins assume that all world mutations, entity accesses, and event
 * handler invocations take place strictly on the server primary thread. Under AGC's multi-core
 * parallel world ticking and async workload pooling, unsafe async Bukkit calls from legacy plugins
 * could cause ConcurrentModificationExceptions or data races.</p>
 *
 * <p>This guard intercepts off-primary-thread API calls and defers them into a FIFO mailbox that
 * is drained strictly on the primary server thread by {@link #drainMailbox(int)}. The previous
 * implementation routed "bridged" work through an async scheduler pool, which did not actually
 * move execution onto the primary thread; the mailbox design fixes that contract.</p>
 *
 * <p>Drain wiring: the primary thread must call {@link #drainMailbox(int)} once per tick
 * (wired post-world-tick barrier in {@code MinecraftServer#tickChildren}).</p>
 */
public final class AgcPluginSafetyGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcPluginSafetyGuard.class);
    private static final AgcPluginSafetyGuard INSTANCE = new AgcPluginSafetyGuard();

    private volatile Thread primaryThread;
    private final AtomicLong asyncCallsIntercepted = new AtomicLong();
    private final AtomicLong bridgesExecuted = new AtomicLong();
    private final AtomicLong peakDepth = new AtomicLong();

    /** FIFO mailbox of operations awaiting execution on the primary thread. */
    private final ConcurrentLinkedQueue<PendingOp> mailbox = new ConcurrentLinkedQueue<>();

    private final java.util.concurrent.locks.ReentrantLock pluginExecutionLock = new java.util.concurrent.locks.ReentrantLock();
    private final AtomicLong pluginLockWaitNanos = new AtomicLong();
    private final AtomicLong pluginInvocationsProtected = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean offPrimaryListenerWarning = new java.util.concurrent.atomic.AtomicBoolean();

    public static AgcPluginSafetyGuard get() {
        return INSTANCE;
    }

    private AgcPluginSafetyGuard() {}

    /**
     * Binds the server primary thread.
     */
    public void bindPrimaryThread(final Thread thread) {
        this.primaryThread = thread;
    }

    /**
     * Checks if the calling thread is the server primary thread.
     *
     * <p>Unbound state (primary thread not yet known) fails open and reports {@code true},
     * matching pre-guard legacy behavior during early bootstrap.</p>
     */
    public boolean isPrimaryThread() {
        final Thread main = this.primaryThread;
        // AGC - honesty fix: this used to report true for *any* TickThread (every parallel-world
        // worker is one) and for any thread inside a virtual-primary context, which made
        // Bukkit.isPrimaryThread() a lie on worker threads. The primary thread is the bound server
        // thread and nothing else; off-primary callers must defer through #ensurePrimaryThread.
        return main == null || Thread.currentThread() == main;
    }

    /**
     * Executes a synchronous plugin event listener, enforcing the Bukkit single-threaded contract.
     * If the current thread is the server primary thread, execution runs immediately without lock contention.
     * On parallel world tick worker threads, serializes listener execution under a reentrant lock and
     * enters a virtual primary execution context so that plugins never experience concurrent modifications
     * or fail primary-thread assertions.
     *
     * @param plugin The target plugin
     * @param action The listener action to execute
     */
    public void executeSynchronousPluginListener(final Object plugin, final Runnable action) {
        if (action == null) {
            return;
        }

        final Thread main = this.primaryThread;
        final boolean isMain = main != null && Thread.currentThread() == main;
        final boolean parallelActive = AgcParallelWorldTickEngine.get().isParallelPhaseActive();

        if (isMain && !parallelActive) {
            action.run();
            return;
        }

        this.pluginInvocationsProtected.incrementAndGet();
        if (this.offPrimaryListenerWarning.compareAndSet(false, true)) {
            LOGGER.warn("AGC: a synchronous Bukkit event listener is executing on a non-primary thread (parallelWorldTick). "
                + "AGC serializes these invocations, but the Bukkit contract (isPrimaryThread()) is NOT satisfied for plugins. "
                + "Keep parallelWorldTick disabled when plugins are installed.");
        }
        final long start = System.nanoTime();
        this.pluginExecutionLock.lock();
        try {
            this.pluginLockWaitNanos.addAndGet(System.nanoTime() - start);
            action.run();
        } finally {
            this.pluginExecutionLock.unlock();
        }
    }

    /**
     * Executes a runnable on the primary thread. If already on the primary thread this runs
     * synchronously; otherwise the action is enqueued into the primary-thread mailbox and will be
     * executed by the next {@link #drainMailbox(int)} call on the primary thread (typically within
     * the same or next tick, mirroring Bukkit scheduler cross-thread semantics).
     *
     * @param action The action to execute
     */
    public void ensurePrimaryThread(final Runnable action) {
        if (action == null) {
            return;
        }

        if (isPrimaryThread()) {
            action.run();
            return;
        }

        this.asyncCallsIntercepted.incrementAndGet();
        this.enqueue("ensure", () -> {
            action.run();
            this.bridgesExecuted.incrementAndGet();
        });
    }

    /**
     * Evaluates a value supplier on the primary thread.
     *
     * @param supplier Value supplier
     * @param <T>      Return type
     * @return CompletableFuture holding the computed result; completed when the primary thread drains it
     */
    public <T> CompletableFuture<T> supplyOnPrimaryThread(final Supplier<T> supplier) {
        if (supplier == null) {
            return CompletableFuture.completedFuture(null);
        }

        if (isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (final Throwable t) {
                final CompletableFuture<T> future = new CompletableFuture<>();
                future.completeExceptionally(t);
                return future;
            }
        }

        this.asyncCallsIntercepted.incrementAndGet();
        final CompletableFuture<T> future = new CompletableFuture<>();
        this.enqueue("supply", () -> {
            try {
                future.complete(supplier.get());
                this.bridgesExecuted.incrementAndGet();
            } catch (final Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Drains pending mailbox operations in FIFO order. Must be invoked on the primary server
     * thread only — executing deferred plugin-facing mutations anywhere else would reintroduce
     * exactly the race conditions this guard exists to prevent.
     *
     * @param maxOperations Maximum operations to drain in this pass (<= 0 for unlimited)
     * @return Number of operations executed
     */
    public int drainMailbox(final int maxOperations) {
        if (!this.isPrimaryThread()) {
            throw new IllegalStateException(
                "AGC PluginSafetyGuard mailbox drain attempted off the primary thread by "
                    + Thread.currentThread().getName());
        }

        int executed = 0;
        final int limit = (maxOperations <= 0) ? Integer.MAX_VALUE : maxOperations;

        PendingOp op;
        while (executed < limit && (op = this.mailbox.poll()) != null) {
            try {
                op.action().run();
            } catch (final Throwable t) {
                LOGGER.error("AGC PluginSafetyGuard bridged operation [{}] failed on primary thread",
                    op.description(), t);
            }
            executed++;
        }
        return executed;
    }

    private void enqueue(final String tag, final Runnable action) {
        this.mailbox.offer(new PendingOp(tag, action, System.nanoTime()));
        final long currentSize = this.mailbox.size();
        long currentPeak = this.peakDepth.get();
        while (currentSize > currentPeak) {
            if (this.peakDepth.compareAndSet(currentPeak, currentSize)) {
                break;
            }
            currentPeak = this.peakDepth.get();
        }
    }

    /** Number of operations currently waiting in the mailbox. */
    public int pendingCount() {
        return this.mailbox.size();
    }

    public void resetMetrics() {
        this.mailbox.clear();
        this.asyncCallsIntercepted.set(0);
        this.bridgesExecuted.set(0);
        this.peakDepth.set(0);
        this.pluginLockWaitNanos.set(0);
        this.pluginInvocationsProtected.set(0);
        this.primaryThread = null;
    }

    public long pluginInvocationsProtected() {
        return this.pluginInvocationsProtected.get();
    }

    public long pluginLockWaitNanos() {
        return this.pluginLockWaitNanos.get();
    }

    public GuardMetrics metrics() {
        return new GuardMetrics(
            this.primaryThread != null ? this.primaryThread.getName() : "unbound",
            this.asyncCallsIntercepted.get(),
            this.bridgesExecuted.get(),
            this.pluginInvocationsProtected.get()
        );
    }

    public record PendingOp(String description, Runnable action, long enqueuedNanos) {
    }

    public record GuardMetrics(
        String primaryThreadName,
        long asyncCallsIntercepted,
        long bridgesExecuted,
        long pluginInvocationsProtected
    ) {
    }
}
