package io.papermc.paper.agc.villager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Villager AI, POI Spatial Index, Gossip & Trading Subsystem Optimizer.
 *
 * <p>Mitigates severe CPU bottlenecks caused by clustered villager trading halls and iron farms:
 * <ul>
 *   <li><b>Workstation POI Query Caching:</b> Caches POI availability for 40 ticks, eliminating per-tick chunk scans.</li>
 *   <li><b>Trade Price Caching:</b> Evaluates supply/demand and gossip discounts only upon trading events.</li>
 *   <li><b>Pathfinding Rate Limiter:</b> Enforces a 10-tick interval floor between villager navigation recalculations.</li>
 *   <li><b>Gossip Dissemination Throttling:</b> Prevents O(N^2) pairwise gossip recalculations in dense trading halls.</li>
 *   <li><b>Iron Golem Spawn Throttler:</b> Caches panic status and throttles golem spawn checks.</li>
 * </ul>
 * </p>
 */
public final class AgcVillagerOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcVillagerOptimizer.class);
    private static final AgcVillagerOptimizer INSTANCE = new AgcVillagerOptimizer();

    public static final long POI_CACHE_TTL_TICKS = 40L;
    public static final long PATHFINDING_INTERVAL_TICKS = 10L;
    public static final long GOSSIP_COOLDOWN_TICKS = 200L;
    public static final long GOLEM_SPAWN_CHECK_INTERVAL_TICKS = 100L;

    private final ConcurrentHashMap<Long, PoiCacheEntry> poiCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastPathfindTick = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> tradePriceCache = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, Long> gossipCooldowns = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, Long> golemCheckCooldowns = new ConcurrentHashMap<>();

    private final AtomicLong poiQueriesSaved = new AtomicLong();
    private final AtomicLong pathfindingThrottled = new AtomicLong();
    private final AtomicLong priceCacheHits = new AtomicLong();
    private final AtomicLong gossipThrottled = new AtomicLong();
    private final AtomicLong golemChecksThrottled = new AtomicLong();

    public static final int MAX_ENTRIES = 16384;

    public static AgcVillagerOptimizer get() {
        return INSTANCE;
    }

    private AgcVillagerOptimizer() {}

    /**
     * Checks if POI workstation scan can be skipped using cached availability.
     */
    public boolean shouldThrottlePoiQuery(final long villagerId, final long currentTick) {
        final PoiCacheEntry entry = this.poiCache.get(villagerId);
        if (entry != null && (currentTick - entry.cachedTick) < POI_CACHE_TTL_TICKS) {
            this.poiQueriesSaved.incrementAndGet();
            return true;
        }
        return false;
    }

    public void recordPoiOutcome(final long villagerId, final long currentTick, final boolean foundPoi) {
        if (this.poiCache.size() > MAX_ENTRIES) {
            this.poiCache.clear();
        }
        this.poiCache.put(villagerId, new PoiCacheEntry(currentTick, foundPoi));
    }

    /**
     * Checks whether villager navigation should be throttled.
     */
    public boolean canExecutePathfinding(final long villagerId, final long currentTick) {
        final Long last = this.lastPathfindTick.get(villagerId);
        if (last != null && (currentTick - last) < PATHFINDING_INTERVAL_TICKS) {
            this.pathfindingThrottled.incrementAndGet();
            return false;
        }
        if (this.lastPathfindTick.size() > MAX_ENTRIES) {
            this.lastPathfindTick.clear();
        }
        this.lastPathfindTick.put(villagerId, currentTick);
        return true;
    }

    /**
     * Retrieves or calculates trade price modifier for a villager-player pair.
     */
    public int getCachedTradePrice(final long tradeKey, final java.util.function.IntSupplier priceComputer) {
        final Integer cached = this.tradePriceCache.get(tradeKey);
        if (cached != null) {
            this.priceCacheHits.incrementAndGet();
            return cached;
        }
        final int computed = priceComputer != null ? priceComputer.getAsInt() : 0;
        if (this.tradePriceCache.size() > MAX_ENTRIES) {
            this.tradePriceCache.clear();
        }
        this.tradePriceCache.put(tradeKey, computed);
        return computed;
    }

    public void invalidateTradePrice(final long tradeKey) {
        this.tradePriceCache.remove(tradeKey);
    }

    /**
     * Checks if villager brain tick can be skipped for stationary trading hall villagers.
     */
    public boolean canSkipBrainTick(final net.minecraft.world.entity.npc.villager.Villager villager, final long currentTick) {
        if (villager.isTrading() || villager.hurtTime > 0) {
            return false;
        }
        if (villager.isPassenger() || (villager.onGround() && villager.getDeltaMovement().horizontalDistanceSqr() < 1.0E-5)) {
            return (currentTick + villager.getId()) % 20 != 0;
        }
        return false;
    }

    /**
     * Cleans up all cached metadata when a villager entity is despawned or unloaded.
     */
    public void invalidateVillager(final long villagerId) {
        this.poiCache.remove(villagerId);
        this.lastPathfindTick.remove(villagerId);
        this.golemCheckCooldowns.remove(villagerId);
    }

    /**
     * Packs two villager entity IDs into a canonical symmetric 64-bit pair key without collisions.
     */
    public static long packPairKey(final long id1, final long id2) {
        final long min = Math.min(id1, id2);
        final long max = Math.max(id1, id2);
        return ((min & 0xFFFFFFFFL) << 32) | (max & 0xFFFFFFFFL);
    }

    /**
     * Checks if pairwise gossip sharing between two villagers can proceed.
     */
    public boolean canShareGossip(final long villager1, final long villager2, final long currentTick) {
        final long pairKey = packPairKey(villager1, villager2);
        final Long last = this.gossipCooldowns.get(pairKey);
        if (last != null && (currentTick - last) < GOSSIP_COOLDOWN_TICKS) {
            this.gossipThrottled.incrementAndGet();
            return false;
        }
        if (this.gossipCooldowns.size() > MAX_ENTRIES) {
            this.gossipCooldowns.clear();
        }
        this.gossipCooldowns.put(pairKey, currentTick);
        return true;
    }

    /**
     * Checks whether an iron golem spawn check can be executed.
     */
    public boolean canCheckGolemSpawn(final long villagerId, final long currentTick) {
        final Long last = this.golemCheckCooldowns.get(villagerId);
        if (last != null && (currentTick - last) < GOLEM_SPAWN_CHECK_INTERVAL_TICKS) {
            this.golemChecksThrottled.incrementAndGet();
            return false;
        }
        if (this.golemCheckCooldowns.size() > MAX_ENTRIES) {
            this.golemCheckCooldowns.clear();
        }
        this.golemCheckCooldowns.put(villagerId, currentTick);
        return true;
    }

    public void clear() {
        this.poiCache.clear();
        this.lastPathfindTick.clear();
        this.tradePriceCache.clear();
        this.gossipCooldowns.clear();
        this.golemCheckCooldowns.clear();
        this.poiQueriesSaved.set(0);
        this.pathfindingThrottled.set(0);
        this.priceCacheHits.set(0);
        this.gossipThrottled.set(0);
        this.golemChecksThrottled.set(0);
    }

    public record PoiCacheEntry(long cachedTick, boolean foundPoi) {}

    public VillagerOptimizerMetrics metrics() {
        return new VillagerOptimizerMetrics(
            this.poiCache.size(),
            this.tradePriceCache.size(),
            this.gossipCooldowns.size(),
            this.poiQueriesSaved.get(),
            this.pathfindingThrottled.get(),
            this.priceCacheHits.get(),
            this.gossipThrottled.get(),
            this.golemChecksThrottled.get()
        );
    }

    public record VillagerOptimizerMetrics(
        int poiCacheSize,
        int priceCacheSize,
        int gossipPairsTracked,
        long poiQueriesSaved,
        long pathfindingThrottled,
        long priceCacheHits,
        long gossipThrottled,
        long golemChecksThrottled
    ) {}
}
