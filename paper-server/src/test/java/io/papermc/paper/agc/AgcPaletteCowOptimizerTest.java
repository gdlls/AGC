package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcPaletteCowOptimizer}.
 */
class AgcPaletteCowOptimizerTest {

    @BeforeEach
    @AfterEach
    void resetOptimizer() {
        AgcPaletteCowOptimizer.get().resetMetrics();
    }

    @Test
    void optimizeUniformSectionReturnsSharedSingleton() {
        final var opt = AgcPaletteCowOptimizer.get();
        final int[] airSection = new int[4096]; // all 0 (air)

        final var res = opt.optimizeSection(airSection);
        assertTrue(res.isShared());
        assertTrue(res.palette().isSingleState());
        assertEquals(0, res.palette().singleStateId());
        assertEquals(0, res.palette().get(0, 0, 0));
        assertEquals(0, res.palette().get(15, 15, 15));

        final var metrics = opt.metrics();
        assertEquals(1, metrics.sectionsOptimized());
        assertTrue(metrics.estimatedBytesSaved() > 0);
    }

    @Test
    void mutatingSharedPaletteTriggersCowExpansion() {
        final var opt = AgcPaletteCowOptimizer.get();
        final int[] stoneSection = new int[4096];
        java.util.Arrays.fill(stoneSection, 1); // stone state ID = 1

        final var res = opt.optimizeSection(stoneSection);
        assertTrue(res.isShared());

        // Read unchanged state
        assertEquals(1, res.palette().get(5, 5, 5));

        // Mutate block at (5, 5, 5) to iron_block (state ID = 42)
        final var modifiedPalette = res.palette().set(5, 5, 5, 42);

        // Resulting palette must be a mutable palette with modified value
        assertFalse(modifiedPalette.isSingleState());
        assertEquals(42, modifiedPalette.get(5, 5, 5));
        assertEquals(1, modifiedPalette.get(0, 0, 0)); // Other blocks remain stone

        final var metrics = opt.metrics();
        assertEquals(1, metrics.cowExpansions());
    }

    @Test
    void heterogeneousSectionCreatesMutablePaletteDirectly() {
        final var opt = AgcPaletteCowOptimizer.get();
        final int[] mixedSection = new int[4096];
        mixedSection[100] = 5;

        final var res = opt.optimizeSection(mixedSection);
        assertFalse(res.isShared());
        assertFalse(res.palette().isSingleState());
        assertEquals(5, res.palette().get(4, 0, 6)); // index 100 = (0<<8)|(6<<4)|4
    }
}
