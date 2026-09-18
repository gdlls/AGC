package io.papermc.paper.agc.light;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcStarLightBatchOptimizer}.
 */
public class AgcStarLightBatchOptimizerTest {

    @BeforeEach
    @AfterEach
    public void resetOptimizer() {
        AgcStarLightBatchOptimizer.get().clear();
    }

    @Test
    public void testNibbleArrayRecycling() {
        final AgcStarLightBatchOptimizer optimizer = AgcStarLightBatchOptimizer.get();

        final byte[] nibble1 = optimizer.acquireNibbleArray();
        assertNotNull(nibble1);
        assertEquals(AgcStarLightBatchOptimizer.NIBBLE_ARRAY_BYTES, nibble1.length);

        // Modify content
        nibble1[0] = 0x7F;
        nibble1[100] = 0x3A;

        // Release back to pool
        optimizer.releaseNibbleArray(nibble1);

        // Reacquire: must be zero-filled
        final byte[] nibble2 = optimizer.acquireNibbleArray();
        assertNotNull(nibble2);
        assertEquals(0, nibble2[0]);
        assertEquals(0, nibble2[100]);

        final var metrics = optimizer.metrics();
        assertTrue(metrics.nibblesAcquired() >= 2);
        assertTrue(metrics.nibblesRecycled() >= 1);
    }

    @Test
    public void testLightUpdateCoalescingAndDrain() {
        final AgcStarLightBatchOptimizer optimizer = AgcStarLightBatchOptimizer.get();

        // First update for chunk (10, 5, 20) with blockLight
        final boolean coalesced1 = optimizer.queueCoalescedSectionUpdate(10, 5, 20, true, false);
        assertFalse(coalesced1, "First update should be newly queued");

        // Second update for same section with skyLight -> should coalesce into existing
        final boolean coalesced2 = optimizer.queueCoalescedSectionUpdate(10, 5, 20, false, true);
        assertTrue(coalesced2, "Second update for identical section should coalesce");

        // Different section -> newly queued
        final boolean coalesced3 = optimizer.queueCoalescedSectionUpdate(11, 5, 20, true, true);
        assertFalse(coalesced3, "Different section coordinate should not coalesce");

        final List<AgcStarLightBatchOptimizer.LightSectionUpdate> drained = new ArrayList<>();
        final int count = optimizer.drainPendingUpdates(drained::add);

        assertEquals(2, count);
        assertEquals(2, drained.size());

        // Find the section (10, 5, 20) and verify merged flags
        final AgcStarLightBatchOptimizer.LightSectionUpdate section1 = drained.stream()
            .filter(u -> u.chunkX() == 10 && u.sectionY() == 5 && u.chunkZ() == 20)
            .findFirst()
            .orElse(null);

        assertNotNull(section1);
        assertTrue(section1.blockLight());
        assertTrue(section1.skyLight());

        // Further drain should be empty
        assertEquals(0, optimizer.drainPendingUpdates(null));
    }

    @Test
    public void testSkyLightOcclusionMask() {
        final AgcStarLightBatchOptimizer optimizer = AgcStarLightBatchOptimizer.get();
        final AgcStarLightBatchOptimizer.SkyLightOcclusionMask mask = new AgcStarLightBatchOptimizer.SkyLightOcclusionMask();

        // Initially fully transparent
        assertTrue(mask.isFullyTransparent());
        assertFalse(mask.isFullyOpaque());
        assertTrue(optimizer.isSectionFullyTransparent(mask));

        // Occlude block at local (5, 7)
        mask.setOccluded(5, 7, true);
        assertTrue(mask.isOccluded(5, 7));
        assertFalse(mask.isOccluded(5, 8));
        assertFalse(mask.isFullyTransparent());
        assertFalse(optimizer.isSectionFullyTransparent(mask));

        // Clear occlusion
        mask.setOccluded(5, 7, false);
        assertFalse(mask.isOccluded(5, 7));
        assertTrue(mask.isFullyTransparent());
    }
}
