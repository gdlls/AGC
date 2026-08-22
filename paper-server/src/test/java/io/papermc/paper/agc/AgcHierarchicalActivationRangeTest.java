package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcHierarchicalActivationRange}.
 */
class AgcHierarchicalActivationRangeTest {

    @BeforeEach
    @AfterEach
    void resetMetrics() {
        AgcHierarchicalActivationRange.get().resetMetrics();
    }

    @Test
    void tierCalculationBasedOnDistance() {
        // Passive mob tests (16 / 32 / 64 blocks)
        assertEquals(AgcHierarchicalActivationRange.ActivationTier.ACTIVE,
            AgcHierarchicalActivationRange.get().calculateTier(100.0, false)); // 10 blocks (100 sq)
        assertEquals(AgcHierarchicalActivationRange.ActivationTier.REDUCED,
            AgcHierarchicalActivationRange.get().calculateTier(400.0, false)); // 20 blocks (400 sq)
        assertEquals(AgcHierarchicalActivationRange.ActivationTier.SLOW,
            AgcHierarchicalActivationRange.get().calculateTier(2500.0, false)); // 50 blocks (2500 sq)
        assertEquals(AgcHierarchicalActivationRange.ActivationTier.DORMANT,
            AgcHierarchicalActivationRange.get().calculateTier(10000.0, false)); // 100 blocks (10000 sq)

        // Hostile mob wider range tests (24 / 40 / 72 blocks)
        assertEquals(AgcHierarchicalActivationRange.ActivationTier.ACTIVE,
            AgcHierarchicalActivationRange.get().calculateTier(400.0, true)); // 20 blocks -> ACTIVE for hostile
        assertEquals(AgcHierarchicalActivationRange.ActivationTier.REDUCED,
            AgcHierarchicalActivationRange.get().calculateTier(1200.0, true)); // ~34 blocks -> REDUCED
    }

    @Test
    void shouldTickEntityFollowsTierIntervals() {
        final var ear = AgcHierarchicalActivationRange.get();

        // ACTIVE (ticks every tick)
        assertTrue(ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.ACTIVE, 1));
        assertTrue(ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.ACTIVE, 2));

        // REDUCED (ticks every 2 ticks)
        assertTrue(ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.REDUCED, 2));
        assertFalse(ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.REDUCED, 3));
        assertTrue(ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.REDUCED, 4));

        // SLOW (ticks every 5 ticks)
        assertTrue(ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.SLOW, 5));
        assertFalse(ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.SLOW, 6));
        assertTrue(ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.SLOW, 10));

        // DORMANT (ticks every 20 ticks)
        assertTrue(ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.DORMANT, 20));
        assertFalse(ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.DORMANT, 21));
    }

    @Test
    void metricsTrackSkipRatio() {
        final var ear = AgcHierarchicalActivationRange.get();
        ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.ACTIVE, 1);
        ear.shouldTickEntity(AgcHierarchicalActivationRange.ActivationTier.DORMANT, 1); // skipped

        final var m = ear.metrics();
        assertEquals(1, m.activeTicked());
        assertEquals(1, m.dormantSkipped());
        assertEquals(0.5, m.skipRatio(), 0.001);
    }
}
