package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcPrimitiveCollections}.
 */
class AgcPrimitiveCollectionsTest {

    @Test
    void packAndUnpackChunkCoordinates() {
        final int[] testCoords = new int[] { 0, 1, -1, 100, -100, 1000000, -1000000 };

        for (final int x : testCoords) {
            for (final int z : testCoords) {
                final long packed = AgcPrimitiveCollections.packChunkKey(x, z);
                assertEquals(x, AgcPrimitiveCollections.unpackChunkX(packed));
                assertEquals(z, AgcPrimitiveCollections.unpackChunkZ(packed));
            }
        }
    }

    @Test
    void packAndUnpackBlockCoordinates() {
        final int x = 12345;
        final int y = 64;
        final int z = -54321;

        final long packed = AgcPrimitiveCollections.packBlockPos(x, y, z);
        assertEquals(x, AgcPrimitiveCollections.unpackBlockX(packed));
        assertEquals(y, AgcPrimitiveCollections.unpackBlockY(packed));
        assertEquals(z, AgcPrimitiveCollections.unpackBlockZ(packed));
    }

    @Test
    void intArrayListOperations() {
        final var list = new AgcPrimitiveCollections.IntArrayList(4);
        assertTrue(list.isEmpty());
        assertEquals(0, list.size());

        for (int i = 0; i < 100; i++) {
            list.add(i * 10);
        }

        assertFalse(list.isEmpty());
        assertEquals(100, list.size());
        assertEquals(0, list.get(0));
        assertEquals(500, list.get(50));
        assertEquals(990, list.get(99));

        final int[] array = list.toArray();
        assertEquals(100, array.length);
        assertEquals(500, array[50]);

        list.clear();
        assertEquals(0, list.size());
        assertTrue(list.isEmpty());
    }
}
