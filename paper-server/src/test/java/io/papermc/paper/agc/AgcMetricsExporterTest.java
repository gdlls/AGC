package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcMetricsExporter}.
 */
class AgcMetricsExporterTest {

    @Test
    void exportAllContainsAllSubsystemCategories() {
        final Map<String, Object> root = AgcMetricsExporter.get().exportAll();

        assertNotNull(root);
        assertTrue(root.containsKey("mode"));
        assertTrue(root.containsKey("governor_state"));
        assertTrue(root.containsKey("world_engine"));
        assertTrue(root.containsKey("network"));
        assertTrue(root.containsKey("entity_ai"));
        assertTrue(root.containsKey("chunk_worlds"));
        assertTrue(root.containsKey("memory"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> worldEngine = (Map<String, Object>) root.get("world_engine");
        assertNotNull(worldEngine);
        assertTrue(worldEngine.containsKey("running"));
        assertTrue(worldEngine.containsKey("workers"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> network = (Map<String, Object>) root.get("network");
        assertNotNull(network);
        assertTrue(network.containsKey("broadcasts"));
        assertTrue(network.containsKey("serializations_saved"));
    }
}
