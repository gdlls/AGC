package io.papermc.paper.agc.ds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcSpatialGridTest {

    @BeforeEach
    public void setup() {
        AgcSpatialGrid.get().clear();
    }

    @Test
    public void testInsertionAndRadiusQuery() {
        AgcSpatialGrid grid = AgcSpatialGrid.get();

        grid.insert(1, 10.0, 64.0, 10.0, "Entity1");
        grid.insert(2, 12.0, 64.0, 12.0, "Entity2");
        grid.insert(3, 100.0, 64.0, 100.0, "FarEntity");

        List<String> results = new ArrayList<>();
        int found = grid.queryRadius(10.0, 64.0, 10.0, 5.0, results);

        assertEquals(2, found);
        assertTrue(results.contains("Entity1"));
        assertTrue(results.contains("Entity2"));
        assertEquals(3, grid.metrics().elementsIndexed());
    }

    @Test
    public void testRemoval() {
        AgcSpatialGrid grid = AgcSpatialGrid.get();

        grid.insert(1, 10.0, 64.0, 10.0, "Entity1");
        boolean removed = grid.remove(1, 10.0, 10.0);
        assertTrue(removed);

        List<String> results = new ArrayList<>();
        int found = grid.queryRadius(10.0, 64.0, 10.0, 5.0, results);
        assertEquals(0, found);
    }

    @Test
    public void testNearbyPairDetection() {
        AgcSpatialGrid grid = AgcSpatialGrid.get();

        grid.insert(1, 10.0, 64.0, 10.0, "ItemA");
        grid.insert(2, 11.0, 64.0, 10.0, "ItemB");

        AtomicInteger pairCount = new AtomicInteger(0);
        grid.forEachNearbyPair(2.0, (a, b) -> pairCount.incrementAndGet());

        assertEquals(1, pairCount.get());
    }
}
