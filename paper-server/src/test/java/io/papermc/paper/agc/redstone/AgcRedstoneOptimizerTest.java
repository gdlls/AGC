package io.papermc.paper.agc.redstone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AgcRedstoneOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcRedstoneOptimizer.get().clear();
    }

    @Test
    public void testRedundantUpdateFiltering() {
        final AgcRedstoneOptimizer optimizer = AgcRedstoneOptimizer.get();

        // Same power -> filtered
        assertTrue(optimizer.filterRedundantUpdate(15, 15));
        assertTrue(optimizer.filterRedundantUpdate(0, 0));
        assertEquals(2, optimizer.metrics().redundantUpdatesFiltered());

        // Changed power -> not filtered
        assertFalse(optimizer.filterRedundantUpdate(15, 14));
        assertFalse(optimizer.filterRedundantUpdate(0, 1));
    }

    @Test
    public void testObserverRecursionDepthLimiter() {
        final AgcRedstoneOptimizer optimizer = AgcRedstoneOptimizer.get();

        // 64 iterations should be allowed
        for (int i = 0; i < AgcRedstoneOptimizer.MAX_OBSERVER_CHAIN_DEPTH; i++) {
            assertTrue(optimizer.checkObserverChainDepth(), "Step " + i + " should be allowed");
        }

        // 65th iteration should be blocked to prevent infinite lag loops
        assertFalse(optimizer.checkObserverChainDepth(), "Step 65 should be blocked");
        assertEquals(1, optimizer.metrics().observerLoopsBroken());

        // Reset depth
        optimizer.resetObserverChainDepth();
        assertTrue(optimizer.checkObserverChainDepth());
    }

    @Test
    public void testBoundarySignalCaching() {
        final AgcRedstoneOptimizer optimizer = AgcRedstoneOptimizer.get();
        final long packedPos = 123456789L;

        final byte signal1 = optimizer.getOrCacheBoundarySignal(packedPos, () -> (byte) 15);
        assertEquals((byte) 15, signal1);

        // Second call should hit cache
        final byte signal2 = optimizer.getOrCacheBoundarySignal(packedPos, () -> (byte) 0);
        assertEquals((byte) 15, signal2);
        assertEquals(1, optimizer.metrics().boundaryCacheHits());

        // Invalidate
        optimizer.invalidateBoundarySignal(packedPos);
        final byte signal3 = optimizer.getOrCacheBoundarySignal(packedPos, () -> (byte) 7);
        assertEquals((byte) 7, signal3);
    }

    @Test
    public void testClockLagMachineSuppression() {
        final AgcRedstoneOptimizer optimizer = AgcRedstoneOptimizer.get();
        final long clockPos = 889900L;

        // Normal flips within limit (< 50 in 20 ticks)
        for (int i = 0; i < 50; i++) {
            assertTrue(optimizer.trackClockFrequency(clockPos, 100L));
        }

        // 51st flip in same tick window -> suppressed!
        assertFalse(optimizer.trackClockFrequency(clockPos, 100L));
        assertEquals(1, optimizer.metrics().clockLagMachinesSuppressed());

        // After window advances -> reset and permitted
        assertTrue(optimizer.trackClockFrequency(clockPos, 125L));
    }

    @Test
    public void testWireUpdateCoalescingAndDrain() {
        final AgcRedstoneOptimizer optimizer = AgcRedstoneOptimizer.get();

        optimizer.coalesceWireUpdate(1001L, (byte) 10);
        optimizer.coalesceWireUpdate(1001L, (byte) 15); // Overwrite with latest power
        optimizer.coalesceWireUpdate(1002L, (byte) 7);

        assertEquals(1, optimizer.metrics().wireUpdatesCoalesced());

        final Map<Long, Byte> drained = new HashMap<>();
        final int count = optimizer.drainCoalescedWireUpdates(drained::put);

        assertEquals(2, count);
        assertEquals((byte) 15, drained.get(1001L));
        assertEquals((byte) 7, drained.get(1002L));
    }
}
