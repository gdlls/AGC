package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcPanamaOffHeapChunkStorageTest {

    @BeforeEach
    void setUp() {
        AgcPanamaOffHeapChunkStorage.get().clear();
    }

    @Test
    void testOffHeapAllocationAndBlockReadWrite() {
        assertNull(AgcPanamaOffHeapChunkStorage.get().getBlockState("world", 0, 0, 0, 0) == 0 ? null : (short) 1);

        // Write block state 255 to chunk (0,0), section 5, index 1024
        AgcPanamaOffHeapChunkStorage.get().setBlockState("world", 0, 0, 5, 1024, (short) 255);

        final short readVal = AgcPanamaOffHeapChunkStorage.get().getBlockState("world", 0, 0, 5, 1024);
        assertEquals((short) 255, readVal);

        final AgcPanamaOffHeapChunkStorage.OffHeapMetrics metrics = AgcPanamaOffHeapChunkStorage.get().metrics();
        assertEquals(1, metrics.activeWorlds());
        assertEquals(1, metrics.loadedChunks());
        assertTrue(metrics.totalAllocatedBytes() > 0);
        assertEquals(1, metrics.totalOffHeapWrites());
        assertEquals(1, metrics.totalOffHeapReads());
    }

    @Test
    void testChunkUnloadClosesArenaAndReclaimsMemory() {
        AgcPanamaOffHeapChunkStorage.get().setBlockState("world", 1, 1, 0, 0, (short) 1);
        final AgcPanamaOffHeapChunkStorage.NativeChunkEntry entry =
            AgcPanamaOffHeapChunkStorage.get().getOrCreateChunk("world", 1, 1);
        assertNotNull(entry);
        assertNotNull(entry.arena());
        assertTrue(entry.segment().scope().isAlive());

        // Unload chunk
        AgcPanamaOffHeapChunkStorage.get().removeChunk("world", 1, 1);
        assertFalse(entry.segment().scope().isAlive(), "Arena must be closed and native segment scope invalidated upon unload");
        assertEquals(0, AgcPanamaOffHeapChunkStorage.get().metrics().loadedChunks());
        assertEquals(0, AgcPanamaOffHeapChunkStorage.get().metrics().totalAllocatedBytes());
    }
}
