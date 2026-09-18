package io.papermc.paper.agc.worldgen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcFeatureOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcFeatureOptimizer.get().clear();
    }

    @Test
    public void testHeightBoundaries() {
        AgcFeatureOptimizer opt = AgcFeatureOptimizer.get();

        // Valid height 64 (-64..320)
        assertTrue(opt.isValidHeight(64, -64, 320));

        // Invalid height 400
        assertFalse(opt.isValidHeight(400, -64, 320));

        // Invalid height -100
        assertFalse(opt.isValidHeight(-100, -64, 320));

        assertEquals(1, opt.metrics().featuresPlaced());
        assertEquals(2, opt.metrics().earlyExits());
    }

    @Test
    public void testBiomeFeaturesCaching() {
        AgcFeatureOptimizer opt = AgcFeatureOptimizer.get();
        AtomicInteger buildCount = new AtomicInteger(0);

        List<String> f1 = opt.getOrCreateBiomeFeatures("plains", () -> {
            buildCount.incrementAndGet();
            return List.of("oak_tree", "flower_patch");
        });
        assertEquals(2, f1.size());
        assertEquals(1, buildCount.get());

        // Cache hit
        List<String> f2 = opt.getOrCreateBiomeFeatures("plains", () -> {
            buildCount.incrementAndGet();
            return List.of("desert_cactus");
        });
        assertEquals(2, f2.size());
        assertEquals(1, buildCount.get());
        assertEquals(1, opt.metrics().biomeCacheHits());
    }
}
