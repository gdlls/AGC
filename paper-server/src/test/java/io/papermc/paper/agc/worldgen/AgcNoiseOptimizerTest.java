package io.papermc.paper.agc.worldgen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class AgcNoiseOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcNoiseOptimizer.get().clear();
    }

    @Test
    public void testInterpolatedDensity() {
        AgcNoiseOptimizer opt = AgcNoiseOptimizer.get();

        double[] sampleGrid = new double[64];
        for (int i = 0; i < 64; i++) {
            sampleGrid[i] = i * 0.5;
        }

        double val1 = opt.getInterpolatedDensity(100L, 2, 64, 2, () -> sampleGrid);
        double val2 = opt.getInterpolatedDensity(100L, 2, 64, 2, () -> sampleGrid);

        assertEquals(val1, val2, 1.0e-9);
        assertEquals(2, opt.metrics().noiseSamples());
        assertEquals(2, opt.metrics().cacheHits());

        // Invalidate chunk grid
        opt.invalidateChunkGrid(100L);
        assertEquals(0, opt.metrics().cachedChunkGrids());
    }
}
