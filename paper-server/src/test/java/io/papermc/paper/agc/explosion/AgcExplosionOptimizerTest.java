package io.papermc.paper.agc.explosion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcExplosionOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcExplosionOptimizer.get().clear();
    }

    @Test
    public void testExplosionCoalescing() {
        AgcExplosionOptimizer opt = AgcExplosionOptimizer.get();

        // 1st explosion at (10.0, 64.0, 10.0) with power 4.0
        var res1 = opt.submitExplosion("world", 10.0, 64.0, 10.0, 4.0f);
        assertFalse(res1.wasCoalesced());
        assertEquals(4.0f, res1.effectivePower(), 1.0e-5);

        // 2nd explosion nearby at (10.5, 64.0, 10.5) in same tick -> coalesced
        var res2 = opt.submitExplosion("world", 10.5, 64.0, 10.5, 4.0f);
        assertTrue(res2.wasCoalesced());
        assertEquals(6.0f, res2.effectivePower(), 1.0e-5); // 4.0 + 4.0 * 0.5 = 6.0
        assertEquals(1, opt.metrics().explosionsCoalesced());

        // Reset on tick end
        opt.onTickEnd();
        var res3 = opt.submitExplosion("world", 10.5, 64.0, 10.5, 4.0f);
        assertFalse(res3.wasCoalesced());
    }

    @Test
    public void testAdaptiveRaysAndBlockDestruction() {
        AgcExplosionOptimizer opt = AgcExplosionOptimizer.get();

        assertEquals(8, opt.getAdaptiveRayCount(1.5f));
        assertEquals(16, opt.getAdaptiveRayCount(4.0f));
        assertEquals(32, opt.getAdaptiveRayCount(10.0f));

        opt.recordBlocksDestroyed(120);
        assertEquals(120, opt.metrics().blocksDestroyedBatched());
    }
}
