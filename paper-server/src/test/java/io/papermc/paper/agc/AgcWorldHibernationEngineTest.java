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
}
