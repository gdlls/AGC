package io.papermc.paper.agc.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcRegionFileManagerTest {

    @BeforeEach
    public void setup() {
        AgcRegionFileManager.get().clear();
    }

    @Test
    public void testLruDescriptorEviction() {
        // Pool with max 2 FDs
        AgcRegionFileManager manager = new AgcRegionFileManager(2);

        var h1 = manager.acquireRegionHandle("r.0.0.mca", () -> new AgcRegionFileManager.ManagedRegionHandle("r.0.0.mca"));
        var h2 = manager.acquireRegionHandle("r.0.1.mca", () -> new AgcRegionFileManager.ManagedRegionHandle("r.0.1.mca"));

        assertNotNull(h1);
        assertNotNull(h2);
        assertFalse(h1.isClosed());
        assertFalse(h2.isClosed());
        assertEquals(2, manager.metrics().openFileDescriptors());

        // Acquire 3rd -> evicts oldest (h1)
        var h3 = manager.acquireRegionHandle("r.0.2.mca", () -> new AgcRegionFileManager.ManagedRegionHandle("r.0.2.mca"));
        assertNotNull(h3);
        assertTrue(h1.isClosed());
        assertFalse(h2.isClosed());
        assertFalse(h3.isClosed());
        assertEquals(2, manager.metrics().openFileDescriptors());
        assertEquals(1, manager.metrics().fdsEvictedLru());
    }

    @Test
    public void testPrefetchHints() {
        AgcRegionFileManager manager = AgcRegionFileManager.get();
        manager.submitPrefetchHint("world", 1, 2);
        assertEquals(1, manager.metrics().prefetchHintsSubmitted());
    }
}
