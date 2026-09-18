package io.papermc.paper.agc.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcPrometheusExporterTest {

    @Test
    public void testPrometheusExpositionFormatting() {
        AgcPrometheusExporter exporter = AgcPrometheusExporter.get();
        String output = exporter.exportPrometheusMetrics();

        assertNotNull(output);
        assertTrue(output.contains("# HELP agc_server_tps"));
        assertTrue(output.contains("# TYPE agc_server_tps gauge"));
        assertTrue(output.contains("agc_server_tps"));
        assertTrue(output.contains("agc_jvm_heap_used_bytes"));
        assertTrue(output.contains("agc_network_workers"));
        assertTrue(output.contains("agc_worlds_total"));
    }
}
