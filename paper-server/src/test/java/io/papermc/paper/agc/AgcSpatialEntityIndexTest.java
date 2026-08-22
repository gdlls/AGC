package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcSpatialEntityIndex}.
 */
class AgcSpatialEntityIndexTest {

    private AgcSpatialEntityIndex<String> index;

    @BeforeEach
    void setUp() {
        this.index = new AgcSpatialEntityIndex<>();
    }

    @Test
    void putAndQueryRadius() {
        this.index.put(1, 0.0, 0.0, "entity1");
        this.index.put(2, 5.0, 5.0, "entity2");
        this.index.put(3, 50.0, 50.0, "entity3");

        assertEquals(3, this.index.totalIndexedEntities());

        final List<String> results = new ArrayList<>();
        this.index.queryRadius(0.0, 0.0, 10.0, results::add);

        assertEquals(2, results.size());
        assertTrue(results.contains("entity1"));
        assertTrue(results.contains("entity2"));
        assertFalse(results.contains("entity3"));
    }

    @Test
    void removeEntityUpdatesIndex() {
        this.index.put(1, 10.0, 10.0, "entity1");
        assertEquals(1, this.index.countInRadius(10.0, 10.0, 5.0));

        this.index.remove(1, 10.0, 10.0);
        assertEquals(0, this.index.countInRadius(10.0, 10.0, 5.0));
        assertEquals(0, this.index.totalIndexedEntities());
    }

    @Test
    void metricsTrackSpatialQueries() {
        this.index.put(1, 0.0, 0.0, "test");
        this.index.countInRadius(0.0, 0.0, 16.0);

        final var m = this.index.metrics();
        assertEquals(1, m.totalQueries());
        assertEquals(1, m.activeChunks());
        assertEquals(1, m.totalEntities());
    }
}
