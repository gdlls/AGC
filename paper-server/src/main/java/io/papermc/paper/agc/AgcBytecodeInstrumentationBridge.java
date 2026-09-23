package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * AGC — High-Throughput Bytecode Instrumentation & Runtime Routing Bridge.
 *
 * <p>Serves as the central runtime target for classloader bytecode transformers (ASM / ByteBuddy)
 * that rewrite legacy Bukkit API call-sites into non-blocking, region-aware AGC primitives.</p>
 *
 * <p>Intercepts and routes:
 * <ul>
 *   <li><b>Bukkit Schedulers:</b> Synchronous tasks are routed directly to the calling region's
 *       context or enqueued to {@link AgcPluginSafetyGuard}.</li>
 *   <li><b>World & Block Mutators:</b> Wrapped in {@link AgcOptimisticTransactionManager} transactions
 *       to eliminate cross-region deadlocks.</li>
 *   <li><b>Event Invocations:</b> Recorded with thread-local world/region context scopes for
 *       diagnostics. No fake primary-thread identity is published: off-primary work is deferred to
 *       the real primary thread via {@link AgcPluginSafetyGuard#ensurePrimaryThread}.</li>
 * </ul>
 * </p>
 */
public final class AgcBytecodeInstrumentationBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcBytecodeInstrumentationBridge.class);
    private static final AgcBytecodeInstrumentationBridge INSTANCE = new AgcBytecodeInstrumentationBridge();

    private final AtomicLong schedulerCallsRouted = new AtomicLong();
    private final AtomicLong blockMutationsRouted = new AtomicLong();
    private final AtomicLong eventsDispatchedVirtual = new AtomicLong();
    private final AtomicLong directFastPathExecutions = new AtomicLong();

    public static AgcBytecodeInstrumentationBridge get() {
        return INSTANCE;
    }

    private AgcBytecodeInstrumentationBridge() {}

    /**
     * Routes a legacy {@code Bukkit.getScheduler().runTask(plugin, runnable)} invocation.
     *
     * @param pluginName Name of the requesting plugin
     * @param task       The task to execute
     */
    public void routeSchedulerTask(final String pluginName, final Runnable task) {
        if (task == null) {
            return;
        }

        this.schedulerCallsRouted.incrementAndGet();

        if (AgcPluginSafetyGuard.get().isPrimaryThread()) {
            this.directFastPathExecutions.incrementAndGet();
            task.run();
            return;
        }

        // Deferred to the real primary thread; no virtual-primary shortcut.
        AgcPluginSafetyGuard.get().ensurePrimaryThread(task);
    }

    /**
     * Routes a block mutation invocation with STM protection.
     *
     * @param worldId        Target world identifier
     * @param packedPos      Target packed block coordinates
     * @param expectedState  Current known state ID
     * @param newState       Target new state ID
     * @param stateApplier   Action to apply the new state
     */
    public void routeBlockMutation(
        final String worldId,
        final long packedPos,
        final int expectedState,
        final int newState,
        final Consumer<Integer> stateApplier
    ) {
        this.blockMutationsRouted.incrementAndGet();
        AgcOptimisticTransactionManager.get().recordBlockMutation(
            worldId, packedPos, expectedState, newState, stateApplier
        );
    }

    /**
     * Executes an event listener within a virtual primary scope to prevent legacy assertion failures.
     *
     * @param worldId   The world context where event occurred
     * @param regionKey The region key
     * @param handler   The event handler runnable
     */
    public void routeEventExecution(final String worldId, final long regionKey, final Runnable handler) {
        if (handler == null) {
            return;
        }
        this.eventsDispatchedVirtual.incrementAndGet();
        if (AgcPluginSafetyGuard.get().isPrimaryThread()) {
            AgcPluginVirtualizer.get().runInContext(worldId, regionKey, handler);
        } else {
            AgcPluginSafetyGuard.get().ensurePrimaryThread(() -> AgcPluginVirtualizer.get().runInContext(worldId, regionKey, handler));
        }
    }

    public void resetMetrics() {
        this.schedulerCallsRouted.set(0);
        this.blockMutationsRouted.set(0);
        this.eventsDispatchedVirtual.set(0);
        this.directFastPathExecutions.set(0);
    }

    public BridgeMetrics metrics() {
        return new BridgeMetrics(
            this.schedulerCallsRouted.get(),
            this.blockMutationsRouted.get(),
            this.eventsDispatchedVirtual.get(),
            this.directFastPathExecutions.get()
        );
    }

    public record BridgeMetrics(
        long schedulerCallsRouted,
        long blockMutationsRouted,
        long eventsDispatchedVirtual,
        long directFastPathExecutions
    ) {
    }
}
