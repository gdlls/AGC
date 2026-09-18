package io.papermc.paper.agc.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcPlayerDataOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcPlayerDataOptimizer.get().clear();
    }

    @Test
    public void testDifferentialSaving() {
        AgcPlayerDataOptimizer opt = AgcPlayerDataOptimizer.get();
        UUID player = UUID.randomUUID();

        // Not dirty -> should skip periodic save
        assertFalse(opt.shouldSaveAndClearDirty(player, false));
        assertEquals(1, opt.metrics().differentialSavesFastPath());

        // Mark inventory dirty -> should save
        opt.markDirty(player, AgcPlayerDataOptimizer.DIRTY_INVENTORY);
        assertTrue(opt.shouldSaveAndClearDirty(player, false));

        // Next pass -> clean again
        assertFalse(opt.shouldSaveAndClearDirty(player, false));
    }

    @Test
    public void testDisconnectUrgentFlush() {
        AgcPlayerDataOptimizer opt = AgcPlayerDataOptimizer.get();
        UUID player = UUID.randomUUID();

        // Disconnect -> always true (urgent)
        assertTrue(opt.shouldSaveAndClearDirty(player, true));
        assertEquals(1, opt.metrics().disconnectUrgentFlushes());
    }
}
