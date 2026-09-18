package io.papermc.paper.agc.spawner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcSpawnerOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcSpawnerOptimizer.get().clear();
    }

    @Test
    public void testDensitySuppression() {
        AgcSpawnerOptimizer opt = AgcSpawnerOptimizer.get();

        // 10 entities in chunk with limit 24 -> allowed
        assertTrue(opt.canSpawnInChunk(1001L, 10, 24));

        // 25 entities in chunk with limit 24 -> suppressed
        assertFalse(opt.canSpawnInChunk(1002L, 25, 24));

        assertEquals(2, opt.metrics().spawnAttempts());
        assertEquals(1, opt.metrics().spawnsAdmitted());
        assertEquals(1, opt.metrics().densitySkips());
    }

    @Test
    public void testCategoryCapLimits() {
        AgcSpawnerOptimizer opt = AgcSpawnerOptimizer.get();

        // Cap of 2 monsters
        assertTrue(opt.tryAdmitCategorySpawn("world", "monster", 2));
        assertTrue(opt.tryAdmitCategorySpawn("world", "monster", 2));
        assertFalse(opt.tryAdmitCategorySpawn("world", "monster", 2));

        // Decrement on despawn
        opt.decrementCategoryCount("world", "monster");
        assertTrue(opt.tryAdmitCategorySpawn("world", "monster", 2));
    }

    @Test
    public void testNonLivingEntitiesExcludedFromMobDensity() {
        // Non-living entities, items, projectiles, armor stands, etc. must not be counted as mobs
        assertFalse(AgcSpawnerOptimizer.isCountedMob(null));
        assertFalse(AgcSpawnerOptimizer.isCountedMob("item_frame"));
        assertFalse(AgcSpawnerOptimizer.isCountedMob(new Object()));

        // Mobs must be admitted when chunk contains non-living clutter (e.g. 100 item frames)
        // because currentDensity passed to canSpawnInChunk only counts Mob instances
        final AgcSpawnerOptimizer opt = AgcSpawnerOptimizer.get();
        final int mobCount = 4; // 4 mobs in a chunk with 100 item frames
        assertTrue(opt.canSpawnInChunk(2001L, mobCount, 24), "Spawning should succeed despite non-living entities in chunk");
    }
}
