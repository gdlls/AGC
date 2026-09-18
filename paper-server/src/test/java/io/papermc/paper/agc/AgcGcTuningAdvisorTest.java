package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcGcTuningAdvisorTest {

    @Test
    public void testRecommendationGeneration() {
        final var rec = AgcGcTuningAdvisor.get().generateRecommendation();
        assertNotNull(rec);
        assertTrue(rec.gcEngine().contains("ZGC"));
        assertNotNull(rec.heapSizing());
        assertFalse(rec.jvmFlags().isEmpty());
        assertTrue(rec.jvmFlags().contains("-XX:+UseZGC"));
        assertTrue(rec.jvmFlags().contains("-XX:+ZGenerational"));
    }

    @Test
    public void testReportRendering() {
        final String report = AgcGcTuningAdvisor.get().renderReport();
        assertNotNull(report);
        assertTrue(report.contains("AGC Intelligent JVM & GC Tuning Advisor"));
        assertTrue(report.contains("-XX:+UseZGC"));
    }
}
