package io.papermc.paper.agc;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * AGC — High-Throughput Plugin Virtualization & Context Manager.
 *
 * <p>Enables legacy Bukkit/Paper plugins to execute transparently across multi-threaded
 * parallel world workers and regionized actor threads without throwing {@link IllegalStateException}
 * or concurrency race conditions.</p>
 *
 * <p>When a worker thread ticks a world or region on behalf of a plugin, it enters an authorized
 * {@link VirtualContext}. In this context, {@link AgcPluginSafetyGuard#isPrimaryThread()} and
 * plugin safety checks evaluate to {@code true}, providing the illusion of a single primary thread
 * while preserving region-level deterministic state isolation.</p>
 */
public final class AgcPluginVirtualizer {

    private static final AgcPluginVirtualizer INSTANCE = new AgcPluginVirtualizer();

    private static final ThreadLocal<VirtualContext> CURRENT_CONTEXT = new ThreadLocal<>();

    private final AtomicLong totalVirtualExecutions = new AtomicLong();
    private final AtomicLong totalContextSwitches = new AtomicLong();

    public static AgcPluginVirtualizer get() {
        return INSTANCE;
    }

    private AgcPluginVirtualizer() {}

    /**
     * Checks if the calling thread is currently executing within an authorized virtual primary context.
     */
    public static boolean isVirtualPrimary() {
        final VirtualContext ctx = CURRENT_CONTEXT.get();
        return ctx != null && ctx.isVirtualPrimary();
    }

    /**
     * Returns the active {@link VirtualContext} on the calling thread, or null if none.
     */
    public static VirtualContext currentContext() {
        return CURRENT_CONTEXT.get();
    }

    /**
     * Enters a virtual primary execution scope for a specific world and region.
     *
     * @param worldId   The world identifier
     * @param regionKey The packed region/chunk key
     * @return AutoCloseable scope that restores previous context upon closing
     */
    public ContextScope enterContext(final String worldId, final long regionKey) {
        final VirtualContext previous = CURRENT_CONTEXT.get();
        final VirtualContext next = new VirtualContext(worldId, regionKey, true, previous);
        CURRENT_CONTEXT.set(next);
        this.totalContextSwitches.incrementAndGet();
        // AGC start - arm the TickThread.isTickThread() fast-path latch before any thread can
        // observe this context (see TickThread#VIRTUAL_POSSIBLE for the full rationale).
        ca.spottedleaf.moonrise.common.util.TickThread.agc$noteVirtualContextPossible();
        // AGC end
        return new ContextScope(previous);
    }

    /**
     * Executes an action within a virtual primary thread context.
     *
     * @param worldId   The world identifier
     * @param regionKey The packed region/chunk key
     * @param action    The action to execute
     */
    public void runInContext(final String worldId, final long regionKey, final Runnable action) {
        Objects.requireNonNull(action, "action");
        try (final ContextScope ignored = enterContext(worldId, regionKey)) {
            this.totalVirtualExecutions.incrementAndGet();
            action.run();
        }
    }

    /**
     * Evaluates a supplier within a virtual primary thread context.
     *
     * @param worldId   The world identifier
     * @param regionKey The packed region/chunk key
     * @param supplier  The supplier to evaluate
     * @param <T>       Return type
     * @return The computed result
     */
    public <T> T supplyInContext(final String worldId, final long regionKey, final Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        try (final ContextScope ignored = enterContext(worldId, regionKey)) {
            this.totalVirtualExecutions.incrementAndGet();
            return supplier.get();
        }
    }

    /**
     * Executes an action within an anonymous virtual primary thread context.
     */
    public void runInVirtualPrimary(final Runnable action) {
        runInContext("virtual-global", 0L, action);
    }

    /**
     * Evaluates a supplier within an anonymous virtual primary thread context.
     */
    public <T> T supplyInVirtualPrimary(final Supplier<T> supplier) {
        return supplyInContext("virtual-global", 0L, supplier);
    }

    public void resetMetrics() {
        this.totalVirtualExecutions.set(0);
        this.totalContextSwitches.set(0);
    }

    public VirtualizerMetrics metrics() {
        return new VirtualizerMetrics(
            this.totalVirtualExecutions.get(),
            this.totalContextSwitches.get()
        );
    }

    /**
     * Immutable snapshot of an active execution context.
     */
    public record VirtualContext(
        String worldId,
        long regionKey,
        boolean isVirtualPrimary,
        VirtualContext parent
    ) {
    }

    /**
     * RAII scope token for resetting ThreadLocal state upon block exit.
     */
    public static final class ContextScope implements AutoCloseable {
        private final VirtualContext previous;
        private boolean closed = false;

        private ContextScope(final VirtualContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                if (this.previous != null) {
                    CURRENT_CONTEXT.set(this.previous);
                } else {
                    CURRENT_CONTEXT.remove();
                }
            }
        }
    }

    public record VirtualizerMetrics(
        long totalVirtualExecutions,
        long totalContextSwitches
    ) {
    }
}
