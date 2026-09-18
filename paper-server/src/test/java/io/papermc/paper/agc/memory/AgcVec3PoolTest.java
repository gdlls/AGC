package io.papermc.paper.agc.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcVec3PoolTest {

    @BeforeEach
    public void setup() {
        AgcVec3Pool.get().clearMetrics();
    }

    @Test
    public void testScratchVectorAcquisitionAndInPlaceMath() {
        AgcVec3Pool pool = AgcVec3Pool.get();

        var vec1 = pool.getScratchVec(3.0, 4.0, 0.0);
        assertNotNull(vec1);
        assertEquals(5.0, vec1.length(), 1e-6);

        vec1.normalize();
        assertEquals(1.0, vec1.length(), 1e-6);
        assertEquals(0.6, vec1.x, 1e-6);
        assertEquals(0.8, vec1.y, 1e-6);

        var vec2 = pool.getScratchVec(1.0, 2.0, 3.0);
        vec2.add(2.0, 3.0, 4.0);
        assertEquals(3.0, vec2.x, 1e-6);
        assertEquals(5.0, vec2.y, 1e-6);
        assertEquals(7.0, vec2.z, 1e-6);

        assertTrue(pool.metrics().vectorAcquires() >= 2);
    }

    @Test
    public void testScratchAabbIntersectsAndInflate() {
        AgcVec3Pool pool = AgcVec3Pool.get();

        var aabb = pool.getScratchAabb(0, 0, 0, 2, 2, 2);
        assertTrue(aabb.intersects(1, 1, 1, 3, 3, 3));
        assertFalse(aabb.intersects(3, 3, 3, 4, 4, 4));

        aabb.inflate(1.5, 1.5, 1.5);
        // Now spans -1.5 to 3.5
        assertTrue(aabb.intersects(3, 3, 3, 4, 4, 4));

        assertEquals(1, pool.metrics().aabbAcquires());
    }
}
