package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcEntityAiBatchProcessor}.
 */
class AgcEntityAiBatchProcessorTest {

    @BeforeEach
    @AfterEach
    void resetMetrics() {
        AgcEntityAiBatchProcessor.get().resetMetrics();
    }

    @Test
    void engagedEntitiesAlwaysEvaluateAi() {
        final var ai = AgcEntityAiBatchProcessor.get();
        // Regardless of tick and entity ID, engaged entities evaluate every tick
        assertTrue(ai.shouldEvaluateAi(100, 1, true));
        assertTrue(ai.shouldEvaluateAi(100, 2, true));
        assertTrue(ai.shouldEvaluateAi(100, 3, true));
        assertTrue(ai.shouldEvaluateAi(100, 4, true));

        final var m = ai.metrics();
        assertEquals(4, m.goalsEvaluated());
        assertEquals(0, m.goalsSkipped());
        assertEquals(0.0, m.cpuReductionRatio());
    }

    @Test
    void idleEntitiesEvaluateRoundRobin() {
        final var ai = AgcEntityAiBatchProcessor.get();
        // Entity ID 0 -> bucket 0 -> runs on ticks 0, 4, 8...
        assertTrue(ai.shouldEvaluateAi(0, 0, false));
        assertFalse(ai.shouldEvaluateAi(0, 1, false));
        assertFalse(ai.shouldEvaluateAi(0, 2, false));
        assertFalse(ai.shouldEvaluateAi(0, 3, false));
        assertTrue(ai.shouldEvaluateAi(0, 4, false));

        // Entity ID 1 -> bucket 1 -> runs on ticks 1, 5, 9...
        assertFalse(ai.shouldEvaluateAi(1, 0, false));
        assertTrue(ai.shouldEvaluateAi(1, 1, false));
        assertFalse(ai.shouldEvaluateAi(1, 2, false));

        final var m = ai.metrics();
        assertEquals(3, m.goalsEvaluated());
        assertEquals(5, m.goalsSkipped());
        assertTrue(m.cpuReductionRatio() > 0.5); // ~62.5% CPU reduction
    }
}
