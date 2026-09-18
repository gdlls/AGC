package io.papermc.paper.agc.villager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgcVillagerOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcVillagerOptimizer.get().clear();
    }

    @Test
    public void testPoiThrottling() {
        final AgcVillagerOptimizer optimizer = AgcVillagerOptimizer.get();
        final long villagerId = 1001L;

        // Tick 0: no cache, should not throttle
        assertFalse(optimizer.shouldThrottlePoiQuery(villagerId, 0L));

        // Record outcome at tick 0
        optimizer.recordPoiOutcome(villagerId, 0L, true);

        // Tick 20: within 40-tick TTL, should throttle
        assertTrue(optimizer.shouldThrottlePoiQuery(villagerId, 20L));
        assertEquals(1, optimizer.metrics().poiQueriesSaved());

        // Tick 41: expired, should not throttle
        assertFalse(optimizer.shouldThrottlePoiQuery(villagerId, 41L));
    }

    @Test
    public void testPathfindingInterval() {
        final AgcVillagerOptimizer optimizer = AgcVillagerOptimizer.get();
        final long villagerId = 2002L;

        // First pathfinding at tick 10 allowed
        assertTrue(optimizer.canExecutePathfinding(villagerId, 10L));

        // Subsequent attempt at tick 15 (< 10-tick interval) throttled
        assertFalse(optimizer.canExecutePathfinding(villagerId, 15L));
        assertEquals(1, optimizer.metrics().pathfindingThrottled());

        // Attempt at tick 20 (>= 10-tick interval) allowed
        assertTrue(optimizer.canExecutePathfinding(villagerId, 20L));
    }

    @Test
    public void testTradePriceCaching() {
        final AgcVillagerOptimizer optimizer = AgcVillagerOptimizer.get();
        final long tradeKey = 3003L;
        final AtomicInteger calculations = new AtomicInteger(0);

        final int price1 = optimizer.getCachedTradePrice(tradeKey, () -> {
            calculations.incrementAndGet();
            return 24;
        });
        assertEquals(24, price1);
        assertEquals(1, calculations.get());

        // Cache hit
        final int price2 = optimizer.getCachedTradePrice(tradeKey, () -> {
            calculations.incrementAndGet();
            return 99;
        });
        assertEquals(24, price2);
        assertEquals(1, calculations.get());
        assertEquals(1, optimizer.metrics().priceCacheHits());

        // Invalidate on trade completion
        optimizer.invalidateTradePrice(tradeKey);
        final int price3 = optimizer.getCachedTradePrice(tradeKey, () -> {
            calculations.incrementAndGet();
            return 28;
        });
        assertEquals(28, price3);
        assertEquals(2, calculations.get());
    }

    @Test
    public void testGossipThrottling() {
        final AgcVillagerOptimizer optimizer = AgcVillagerOptimizer.get();

        // First gossip at tick 10 allowed
        assertTrue(optimizer.canShareGossip(101L, 102L, 10L));

        // Symmetric check: (102, 101) at tick 50 (< 200 ticks) throttled!
        assertFalse(optimizer.canShareGossip(102L, 101L, 50L));
        assertEquals(1, optimizer.metrics().gossipThrottled());

        // After cooldown (tick 211) allowed
        assertTrue(optimizer.canShareGossip(101L, 102L, 211L));
    }

    @Test
    public void testGolemSpawnCheckRateLimiting() {
        final AgcVillagerOptimizer optimizer = AgcVillagerOptimizer.get();
        final long villagerId = 5005L;

        // First check allowed
        assertTrue(optimizer.canCheckGolemSpawn(villagerId, 100L));

        // Immediate subsequent check throttled (< 100 ticks)
        assertFalse(optimizer.canCheckGolemSpawn(villagerId, 150L));
        assertEquals(1, optimizer.metrics().golemChecksThrottled());

        // Check at tick 201 allowed
        assertTrue(optimizer.canCheckGolemSpawn(villagerId, 201L));
    }

    @Test
    public void testInvalidateVillager() {
        final AgcVillagerOptimizer optimizer = AgcVillagerOptimizer.get();
        final long villagerId = 9999L;

        optimizer.recordPoiOutcome(villagerId, 10L, false);
        optimizer.canExecutePathfinding(villagerId, 10L);
        optimizer.canCheckGolemSpawn(villagerId, 10L);

        assertTrue(optimizer.shouldThrottlePoiQuery(villagerId, 15L));
        assertFalse(optimizer.canExecutePathfinding(villagerId, 15L));
        assertFalse(optimizer.canCheckGolemSpawn(villagerId, 15L));

        optimizer.invalidateVillager(villagerId);

        // After invalidation, all cooldowns and cache entries are purged
        assertFalse(optimizer.shouldThrottlePoiQuery(villagerId, 15L));
        assertTrue(optimizer.canExecutePathfinding(villagerId, 15L));
        assertTrue(optimizer.canCheckGolemSpawn(villagerId, 15L));
    }

    @Test
    public void testPackPairKeySymmetricAndCollisionFree() {
        final long k1 = AgcVillagerOptimizer.packPairKey(42L, 999L);
        final long k2 = AgcVillagerOptimizer.packPairKey(999L, 42L);
        assertEquals(k1, k2, "Pair keys must be symmetric");

        final long k3 = AgcVillagerOptimizer.packPairKey(100L, 200L);
        final long k4 = AgcVillagerOptimizer.packPairKey(200L, 100L);
        assertEquals(k3, k4);
        assertNotEquals(k1, k3);
    }
}
