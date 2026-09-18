package io.papermc.paper.agc.ds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class AgcFastHasherTest {

    @BeforeEach
    public void setup() {
        AgcFastHasher.get().clear();
    }

    @Test
    public void testCoordinateHashing() {
        AgcFastHasher hasher = AgcFastHasher.get();

        long h1 = hasher.hashCoords(100, 64, 200);
        long h2 = hasher.hashCoords(100, 65, 200);
        long h3 = hasher.hashCoords(100, 64, 200);

        assertNotEquals(h1, h2);
        assertEquals(h1, h3);

        long ch1 = hasher.hashChunkCoords(10, 20);
        long ch2 = hasher.hashChunkCoords(10, 21);
        assertNotEquals(ch1, ch2);
    }

    @Test
    public void testStringHashing() {
        AgcFastHasher hasher = AgcFastHasher.get();

        long hash1 = hasher.hashString("minecraft:stone");
        long hash2 = hasher.hashString("minecraft:dirt");
        long hash3 = hasher.hashString("minecraft:stone");

        assertNotEquals(0L, hash1);
        assertNotEquals(hash1, hash2);
        assertEquals(hash1, hash3);
    }

    @Test
    public void testStringInterning() {
        AgcFastHasher hasher = AgcFastHasher.get();

        String s1 = new String("minecraft:zombie");
        String s2 = new String("minecraft:zombie");

        assertNotEquals(System.identityHashCode(s1), System.identityHashCode(s2));

        String interned1 = hasher.intern(s1);
        String interned2 = hasher.intern(s2);

        assertNotNull(interned1);
        assertSame(interned1, interned2);
        assertEquals(1, hasher.metrics().internHits());
        assertEquals(1, hasher.metrics().poolSize());
    }
}
