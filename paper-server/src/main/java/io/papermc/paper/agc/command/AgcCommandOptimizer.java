package io.papermc.paper.agc.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Command Processing & Tab Completion Rate Limiting Engine.
 *
 * <p>Protects the server from command execution exploits and tab-completion packet floods:
 * <ul>
 *   <li><b>Tab Completion Throttling:</b> Enforces a 500ms cooldown floor per player for auto-completion queries.</li>
 *   <li><b>Entity Selector Clamping:</b> Clamps {@code @e} selector results to a strict maximum (default 1000) to prevent main-thread freeze.</li>
 *   <li><b>Permission Tree Caching:</b> Caches player-specific command syntax trees.</li>
 * </ul>
 * </p>
 */
public final class AgcCommandOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcCommandOptimizer.class);
    private static final AgcCommandOptimizer INSTANCE = new AgcCommandOptimizer();

    public static final long TAB_COOLDOWN_MS = 500L;
    public static final int DEFAULT_MAX_SELECTOR_ENTITIES = 1000;

    private final java.util.concurrent.atomic.AtomicBoolean selectorClampingEnabled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ConcurrentHashMap<UUID, Long> lastTabRequestTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> commandTreeCache = new ConcurrentHashMap<>();

    private final AtomicLong tabCompletionsThrottled = new AtomicLong();
    private final AtomicLong tabCompletionsAllowed = new AtomicLong();
    private final AtomicLong entitySelectorClamps = new AtomicLong();
    private final AtomicLong treeCacheHits = new AtomicLong();

    public static AgcCommandOptimizer get() {
        return INSTANCE;
    }

    private AgcCommandOptimizer() {}

    /**
     * Checks if a tab completion request should be permitted for the given player.
     *
     * @param playerId Player UUID
     * @return true if permitted, false if throttled
     */
    public boolean canExecuteTabComplete(final UUID playerId) {
        if (playerId == null) return true;
        final long now = System.currentTimeMillis();

        final Long last = this.lastTabRequestTime.get(playerId);
        if (last != null && (now - last) < TAB_COOLDOWN_MS) {
            this.tabCompletionsThrottled.incrementAndGet();
            return false;
        }

        this.lastTabRequestTime.put(playerId, now);
        this.tabCompletionsAllowed.incrementAndGet();
        return true;
    }

    /**
     * Clamps entity selector query result limit.
     *
     * @param requestedCount Requested or discovered entities count
     * @param maxLimit Configured limit (or <= 0 for default 1000)
     * @return Clamped count
     */
    public int clampSelectorResults(final int requestedCount, final int maxLimit) {
        final int limit = maxLimit > 0 ? maxLimit : DEFAULT_MAX_SELECTOR_ENTITIES;
        if (requestedCount > limit) {
            this.entitySelectorClamps.incrementAndGet();
            return limit;
        }
        return requestedCount;
    }

    public boolean isSelectorClampingEnabled() {
        return this.selectorClampingEnabled.get();
    }

    public void setSelectorClampingEnabled(final boolean enabled) {
        this.selectorClampingEnabled.set(enabled);
    }

    /**
     * Retrieves or creates a cached player command tree.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrCreateCommandTree(final UUID playerId, final java.util.function.Supplier<T> treeSupplier) {
        if (playerId == null) return treeSupplier != null ? treeSupplier.get() : null;

        final Object cached = this.commandTreeCache.get(playerId);
        if (cached != null) {
            this.treeCacheHits.incrementAndGet();
            return (T) cached;
        }

        final T created = treeSupplier != null ? treeSupplier.get() : null;
        if (created != null) {
            this.commandTreeCache.put(playerId, created);
        }
        return created;
    }

    /**
     * Invalidates a player's command tree cache upon permission or OP changes.
     */
    public void invalidateTreeCache(final UUID playerId) {
        if (playerId != null) {
            this.commandTreeCache.remove(playerId);
            this.lastTabRequestTime.remove(playerId);
        }
    }

    public void clear() {
        this.lastTabRequestTime.clear();
        this.commandTreeCache.clear();
        this.tabCompletionsThrottled.set(0);
        this.tabCompletionsAllowed.set(0);
        this.entitySelectorClamps.set(0);
        this.treeCacheHits.set(0);
    }

    public CommandOptimizerMetrics metrics() {
        return new CommandOptimizerMetrics(
            this.commandTreeCache.size(),
            this.tabCompletionsAllowed.get(),
            this.tabCompletionsThrottled.get(),
            this.entitySelectorClamps.get(),
            this.treeCacheHits.get()
        );
    }

    public record CommandOptimizerMetrics(
        int cachedTrees,
        long tabCompletionsAllowed,
        long tabCompletionsThrottled,
        long entitySelectorClamps,
        long treeCacheHits
    ) {
    }
}
