package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcWorldHibernationEngine}.
 */
class AgcWorldHibernationEngineTest {

    @BeforeEach
    @AfterEach
    void resetEngine() {
        AgcWorldHibernationEngine.get().resetMetrics();
    }

    @Test
    void activeWorldWithPlayersStaysActive() {
        final var engine = AgcWorldHibernationEngine.get();
        final var state = engine.updateWorld("world_arena_1", 5, 100, 20);

        assertEquals(AgcWorldHibernationEngine.WorldState.ACTIVE, state);
        assertTrue(engine.shouldTickWorld("world_arena_1"));
    }

    @Test
    void emptyWorldTransitionsFromDrainingToHibernating() {
        final var engine = AgcWorldHibernationEngine.get();
        final long graceTicks = 10;

        // Tick 0: 5 players -> ACTIVE
        engine.updateWorld("world_dungeon", 5, 0, graceTicks);
        assertEquals(AgcWorldHibernationEngine.WorldState.ACTIVE, engine.getState("world_dungeon"));

        // Tick 1: 0 players -> DRAINING (grace countdown begins)
        final var state1 = engine.updateWorld("world_dungeon", 0, 1, graceTicks);
        assertEquals(AgcWorldHibernationEngine.WorldState.DRAINING, state1);
        assertTrue(engine.shouldTickWorld("world_dungeon"));

        // Tick 5: Still in grace period -> DRAINING
        final var state2 = engine.updateWorld("world_dungeon", 0, 5, graceTicks);
        assertEquals(AgcWorldHibernationEngine.WorldState.DRAINING, state2);

        // Tick 12: Past grace period (12 - 1 >= 10) -> HIBERNATING
        final var state3 = engine.updateWorld("world_dungeon", 0, 12, graceTicks);
        assertEquals(AgcWorldHibernationEngine.WorldState.HIBERNATING, state3);
        assertFalse(engine.shouldTickWorld("world_dungeon")); // Skipped from ticking

        final var m = engine.metrics();
        assertEquals(1, m.hibernationsTriggered());
    }

    @Test
    void instantWakeupWhenPlayerEntersHibernatingWorld() {
        final var engine = AgcWorldHibernationEngine.get();
        engine.updateWorld("world_plot", 0, 0, 1);
        engine.updateWorld("world_plot", 0, 10, 1); // Enters HIBERNATING

        assertEquals(AgcWorldHibernationEngine.WorldState.HIBERNATING, engine.getState("world_plot"));

        // Player joins -> Instant wake up to ACTIVE
        final var state = engine.updateWorld("world_plot", 1, 15, 1);
        assertEquals(AgcWorldHibernationEngine.WorldState.ACTIVE, state);
        assertTrue(engine.shouldTickWorld("world_plot"));

        final var m = engine.metrics();
        assertEquals(1, m.wakeupsTriggered());
    }

    @Test
    void threeTierColdTransitionAndEviction() {
        final var engine = AgcWorldHibernationEngine.get();
        final java.util.concurrent.atomic.AtomicBoolean evicted = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Tick 0: 1 player -> HOT (ACTIVE)
        engine.updateWorld("world_minigame", 1, 0, 10, 50, () -> evicted.set(true));
        assertEquals(AgcWorldHibernationEngine.Tier.HOT, engine.getTier("world_minigame"));

        // Tick 1: 0 players -> DRAINING
        engine.updateWorld("world_minigame", 0, 1, 10, 50, () -> evicted.set(true));
        assertEquals(AgcWorldHibernationEngine.Tier.HOT, engine.getTier("world_minigame"));

        // Tick 15: WARM (HIBERNATING)
        engine.updateWorld("world_minigame", 0, 15, 10, 50, () -> evicted.set(true));
        assertEquals(AgcWorldHibernationEngine.Tier.WARM, engine.getTier("world_minigame"));
        assertEquals(AgcWorldHibernationEngine.WorldState.HIBERNATING, engine.getState("world_minigame"));
        assertFalse(evicted.get());

        // Tick 60: COLD (DEEP DORMANCY) -> triggers eviction callback
        engine.updateWorld("world_minigame", 0, 60, 10, 50, () -> evicted.set(true));
        assertEquals(AgcWorldHibernationEngine.Tier.COLD, engine.getTier("world_minigame"));
        assertEquals(AgcWorldHibernationEngine.WorldState.COLD, engine.getState("world_minigame"));
        assertTrue(evicted.get());
        assertFalse(engine.shouldTickWorld("world_minigame"));

        // Player joins -> Wakes directly from COLD to ACTIVE
        engine.updateWorld("world_minigame", 1, 65, 10, 50, null);
        assertEquals(AgcWorldHibernationEngine.Tier.HOT, engine.getTier("world_minigame"));
        assertEquals(AgcWorldHibernationEngine.WorldState.ACTIVE, engine.getState("world_minigame"));
    }

    @Test
    void multiWorldBotStressLifecycle() {
        final var engine = AgcWorldHibernationEngine.get();
        final int worldCount = 50;
        final int totalBots = 200;

        // 1. Initial State: Distribute 200 bots across 50 worlds (4 bots per world)
        for (int w = 0; w < worldCount; w++) {
            final String worldName = "arena_" + w;
            final var state = engine.updateWorld(worldName, 4, 0, 10, 100, null);
            assertEquals(AgcWorldHibernationEngine.WorldState.ACTIVE, state);
            assertTrue(engine.shouldTickWorld(worldName));
        }
        assertEquals(worldCount, engine.metrics().activeWorlds());

        // 2. Bots evacuate worlds 10..49 (40 worlds become empty, worlds 0..9 keep 20 bots each)
        for (int w = 10; w < worldCount; w++) {
            engine.updateWorld("arena_" + w, 0, 1, 10, 100, null); // Enters DRAINING
        }
        assertEquals(10, engine.metrics().activeWorlds());
        assertEquals(40, engine.metrics().drainingWorlds());

        // 3. Advance to tick 25: All 40 empty worlds enter WARM (HIBERNATING)
        for (int w = 10; w < worldCount; w++) {
            final var state = engine.updateWorld("arena_" + w, 0, 25, 10, 100, null);
            assertEquals(AgcWorldHibernationEngine.WorldState.HIBERNATING, state);
            assertFalse(engine.shouldTickWorld("arena_" + w));
        }
        assertEquals(40, engine.metrics().warmHibernatingWorlds());

        // 4. Advance to tick 150: All 40 hibernating worlds enter COLD
        final java.util.concurrent.atomic.AtomicInteger coldCallbacks = new java.util.concurrent.atomic.AtomicInteger();
        for (int w = 10; w < worldCount; w++) {
            final var state = engine.updateWorld("arena_" + w, 0, 150, 10, 100, coldCallbacks::incrementAndGet);
            assertEquals(AgcWorldHibernationEngine.WorldState.COLD, state);
            assertFalse(engine.shouldTickWorld("arena_" + w));
        }
        assertEquals(40, coldCallbacks.get());
        assertEquals(40, engine.metrics().coldDormantWorlds());

        // 5. Bot Rush: 200 bots surge into 20 cold worlds (arena_10 .. arena_29)
        for (int w = 10; w < 30; w++) {
            final var state = engine.updateWorld("arena_" + w, 10, 160, 10, 100, null);
            assertEquals(AgcWorldHibernationEngine.WorldState.ACTIVE, state);
            assertTrue(engine.shouldTickWorld("arena_" + w));
        }
        // 10 originally active + 20 woke = 30 active, 20 still cold
        assertEquals(30, engine.metrics().activeWorlds());
        assertEquals(20, engine.metrics().coldDormantWorlds());
        assertEquals(20, engine.metrics().wakeupsTriggered());
    }
}
