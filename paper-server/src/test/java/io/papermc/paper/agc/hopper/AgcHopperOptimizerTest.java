package io.papermc.paper.agc.hopper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcHopperOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcHopperOptimizer.get().clear();
    }

    @Test
    public void testTargetContainerCaching() {
        final AgcHopperOptimizer optimizer = AgcHopperOptimizer.get();
        final long hopperPos = 99887766L;
        final Object dummyChest = new Object();
        final AtomicInteger lookups = new AtomicInteger();

        final Object res1 = optimizer.getOrResolveTargetContainer(hopperPos, () -> {
            lookups.incrementAndGet();
            return dummyChest;
        });
        assertEquals(dummyChest, res1);
        assertEquals(1, lookups.get());

        // Subsequent lookup hits cache
        final Object res2 = optimizer.getOrResolveTargetContainer(hopperPos, () -> {
            lookups.incrementAndGet();
            return new Object();
        });
        assertEquals(dummyChest, res2);
        assertEquals(1, lookups.get());
        assertEquals(1, optimizer.metrics().targetContainerCacheHits());

        // Invalidate on block change
        optimizer.invalidateHopper(hopperPos);
        final Object newChest = new Object();
        final Object res3 = optimizer.getOrResolveTargetContainer(hopperPos, () -> {
            lookups.incrementAndGet();
            return newChest;
        });
        assertEquals(newChest, res3);
        assertEquals(2, lookups.get());
    }

    @Test
    public void testEmptyHopperSleepDormancy() {
        final AgcHopperOptimizer optimizer = AgcHopperOptimizer.get();
        final long hopperPos = 11223344L;

        // First pass: empty hopper with no entities above -> executes once, enters sleep
        assertFalse(optimizer.shouldSkipHopperTick(hopperPos, true, false));
        assertEquals(1, optimizer.metrics().sleepingHoppers());

        // Next 7 passes -> skips tick
        for (int i = 0; i < AgcHopperOptimizer.EMPTY_HOPPER_SLEEP_TICKS - 1; i++) {
            assertTrue(optimizer.shouldSkipHopperTick(hopperPos, true, false), "Pass " + i + " should skip tick");
        }
        assertEquals(AgcHopperOptimizer.EMPTY_HOPPER_SLEEP_TICKS - 1, optimizer.metrics().hopperTicksSkipped());

        // Item entity drops above hopper -> immediately wakes up!
        assertFalse(optimizer.shouldSkipHopperTick(hopperPos, true, true));

        // Hopper has items inside -> wakes up!
        assertFalse(optimizer.shouldSkipHopperTick(hopperPos, false, false));
    }

    @Test
    public void testDoubleChestCaching() {
        final AgcHopperOptimizer optimizer = AgcHopperOptimizer.get();
        final long chestPos = 55667788L;
        final Object pairedChest = new Object();
        final AtomicInteger lookups = new AtomicInteger();

        final Object r1 = optimizer.getOrResolveDoubleChest(chestPos, () -> {
            lookups.incrementAndGet();
            return pairedChest;
        });
        assertEquals(pairedChest, r1);
        assertEquals(1, lookups.get());

        // Cache hit
        final Object r2 = optimizer.getOrResolveDoubleChest(chestPos, () -> {
            lookups.incrementAndGet();
            return new Object();
        });
        assertEquals(pairedChest, r2);
        assertEquals(1, lookups.get());
        assertEquals(1, optimizer.metrics().doubleChestCacheHits());
    }

    @Test
    public void testOccupancyBitmaskFastChecks() {
        final AgcHopperOptimizer optimizer = AgcHopperOptimizer.get();
        final long containerPos = 44556677L;

        // Mask 0L -> completely empty
        optimizer.updateOccupancyMask(containerPos, 0L);
        assertTrue(optimizer.isContainerEmptyFast(containerPos));
        assertFalse(optimizer.isContainerFullFast(containerPos, 27));

        // Mask with slot 0 and slot 5 filled
        optimizer.updateOccupancyMask(containerPos, (1L << 0) | (1L << 5));
        assertFalse(optimizer.isContainerEmptyFast(containerPos));
        assertFalse(optimizer.isContainerFullFast(containerPos, 27));

        // Mask with all 27 slots filled (2^27 - 1)
        final long full27 = (1L << 27) - 1L;
        optimizer.updateOccupancyMask(containerPos, full27);
        assertFalse(optimizer.isContainerEmptyFast(containerPos));
        assertTrue(optimizer.isContainerFullFast(containerPos, 27));

        assertEquals(6, optimizer.metrics().bitmaskFastChecks());
    }
}
