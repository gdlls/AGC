package io.papermc.paper.agc.jvm;

import io.papermc.paper.agc.profiling.AgcMemoryTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcJvmTunerTest {

    @BeforeEach
    public void setup() {
        AgcJvmTuner.get().clear();
    }

    @Test
    public void testCollectorDetectionAndReport() {
        AgcJvmTuner tuner = AgcJvmTuner.get();
        assertNotNull(tuner.getDetectedCollector());

        String report = tuner.generateTuningReport();
        assertNotNull(report);
        assertTrue(report.contains("AGC Intelligent JVM & GC Tuning Advisor"));
    }

    @Test
    public void testGcHealthStatusEvaluation() {
        AgcJvmTuner tuner = AgcJvmTuner.get();

        // Healthy state (5ms pause, 40% memory)
        assertEquals(AgcJvmTuner.GcHealthStatus.HEALTHY, tuner.evaluateHealth(0.40, 5.0));

        // Elevated state (20ms pause, 88% memory)
        assertEquals(AgcJvmTuner.GcHealthStatus.ELEVATED_LOAD, tuner.evaluateHealth(0.88, 20.0));

        // Critical latency spike (80ms pause, 96% memory)
        assertEquals(AgcJvmTuner.GcHealthStatus.CRITICAL_PRESSURE, tuner.evaluateHealth(0.96, 80.0));
        assertEquals(1, tuner.metrics().gcPressureWarnings());

        // Also test with tracker instance
        assertNotNull(tuner.evaluateHealth(AgcMemoryTracker.get()));
    }
}
