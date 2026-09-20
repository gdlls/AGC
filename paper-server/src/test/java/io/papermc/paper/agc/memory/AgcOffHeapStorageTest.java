package io.papermc.paper.agc.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcOffHeapStorageTest {

    @BeforeEach
    public void setup() {
        AgcOffHeapStorage.get().clear();
    }

    @Test
    public void testDirectOffHeapStorageReadWriteEvict() {
        AgcOffHeapStorage storage = AgcOffHeapStorage.get();

        byte[] original = new byte[]{10, 20, 30, 40, 50, 60, 70, 80};
        storage.storeChunkDirect("world_nether", 12, -34, original);

        assertEquals(1, storage.metrics().totalStoredChunks());
        assertEquals(8, storage.metrics().totalAllocatedOffHeapBytes());
        assertEquals(1, storage.metrics().totalOffHeapWrites());

        byte[] buffer = new byte[8];
        int read = storage.readChunkDirect("world_nether", 12, -34, buffer);
        assertEquals(8, read);
        assertArrayEquals(original, buffer);
        assertEquals(1, storage.metrics().totalOffHeapReads());

        storage.evictChunk("world_nether", 12, -34);
        assertEquals(0, storage.metrics().totalStoredChunks());
        assertEquals(0, storage.metrics().totalAllocatedOffHeapBytes());

        int miss = storage.readChunkDirect("world_nether", 12, -34, buffer);
        assertEquals(-1, miss);
    }

    @Test
    public void testSlabBackedRoundTripIsByteIdentical() {
        // OFFHEAP_SLAB_ALLOCATOR is enabled by default: small payloads ride the slab pool.
        assertTrue(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(
            io.papermc.paper.agc.AgcCapabilityMatrix.Feature.OFFHEAP_SLAB_ALLOCATOR));
        final AgcOffHeapStorage storage = AgcOffHeapStorage.get();
        final byte[] original = new byte[1024];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 31);
        }
        storage.storeChunkDirect("world", 1, 2, original);
        final byte[] buffer = new byte[1024];
        assertEquals(1024, storage.readChunkDirect("world", 1, 2, buffer));
        assertArrayEquals(original, buffer);
        storage.evictChunk("world", 1, 2);
        assertEquals(0, storage.metrics().totalStoredChunks());
    }
}
