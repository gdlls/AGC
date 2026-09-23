package io.papermc.paper.agc;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * AGC — Execution Context Bookkeeping (formerly "Plugin Virtualizer").
 *
 * <p>This class records which world/region the calling thread is currently working on, which the
 * AGC transaction bookkeeping uses for diagnostics.</p>
 *
 * <p><b>It no longer publishes a fake primary-thread identity.</b> The previous design made
 * {@link AgcPluginSafetyGuard#isPrimaryThread()} and
 * {@code TickThread.isTickThread()} report {@code true} on parallel world-tick workers, which is
 * precisely the invariant those guards exist to protect: plugins and the chunk system were told a
 * worker was the main thread. Off-thread work must instead be deferred to the real primary thread
 * through {@link AgcPluginSafetyGuard#ensurePrimaryThread}.</p>
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
     * Always {@code false}. Kept as a stable accessor so callers that used to gate behaviour on the
     * old "virtual primary" identity now simply take the real-primary-thread path.
     *
     * @deprecated There is no virtual primary thread any more; use
     *             {@link AgcPluginSafetyGuard#isPrimaryThread()} for the real contract and
     *             {@link AgcPluginSafetyGuard#ensurePrimaryThread} to hop onto the primary thread.
     */
    @Deprecated(forRemoval = false)
    public static boolean isVirtualPrimary() {
        return false;
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
