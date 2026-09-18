package io.papermc.paper.agc.worldgen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcStructureOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcStructureOptimizer.get().clear();
    }

    @Test
    public void testStructureLocationCaching() {
        AgcStructureOptimizer opt = AgcStructureOptimizer.get();
        AtomicInteger computeCount = new AtomicInteger(0);

        var loc1 = opt.getOrCreateStructureLocation("world", "mansion", 10, 20, () -> {
            computeCount.incrementAndGet();
            return new AgcStructureOptimizer.StructureLocationEntry(160, 64, 320, true);
        });

        assertNotNull(loc1);
        assertEquals(1, computeCount.get());

        // Second call -> cached
        var loc2 = opt.getOrCreateStructureLocation("world", "mansion", 10, 20, () -> {
            computeCount.incrementAndGet();
            return new AgcStructureOptimizer.StructureLocationEntry(0, 0, 0, false);
        });

        assertEquals(160, loc2.blockX());
        assertEquals(1, computeCount.get());
        assertEquals(1, opt.metrics().locationCacheHits());
    }

    @Test
    public void testConcurrencyPermits() {
        AgcStructureOptimizer opt = AgcStructureOptimizer.get();

        // Max 2 permits
        assertTrue(opt.tryAcquireStructurePermit(2));
        assertTrue(opt.tryAcquireStructurePermit(2));
        assertFalse(opt.tryAcquireStructurePermit(2));
        assertEquals(1, opt.metrics().placementsThrottled());

        // Release permit
        opt.releaseStructurePermit();
        assertTrue(opt.tryAcquireStructurePermit(2));
    }
}
