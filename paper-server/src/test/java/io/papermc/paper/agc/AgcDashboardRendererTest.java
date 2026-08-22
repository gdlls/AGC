package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcDashboardRenderer}.
 */
class AgcDashboardRendererTest {

    @Test
    void renderDashboardOutputsFormattedLines() {
        final List<String> lines = AgcDashboardRenderer.get().renderDashboard();

        assertNotNull(lines);
        assertFalse(lines.isEmpty());
        assertTrue(lines.size() >= 10);

        final String fullText = String.join("\n", lines);
        assertTrue(fullText.contains("AGC PERFORMANCE DASHBOARD"));
        assertTrue(fullText.contains("Runtime:"));
        assertTrue(fullText.contains("Memory:"));
        assertTrue(fullText.contains("World Engine"));
        assertTrue(fullText.contains("50+ Worlds"));
        assertTrue(fullText.contains("500+ Network"));
        assertTrue(fullText.contains("Entity EAR 2.0"));
        assertTrue(fullText.contains("Entity AI"));
    }
}
