package io.papermc.paper.agc.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcPluginWatchdogTest {

    @BeforeEach
    public void setup() {
        AgcPluginWatchdog.get().clear();
    }

    @Test
    public void testHealthyPluginExecution() {
        AgcPluginWatchdog watchdog = AgcPluginWatchdog.get();

        // 100 microseconds (0.1ms) -> healthy
        boolean healthy = watchdog.recordExecution("Vault", 100_000L);
        assertTrue(healthy);
        assertFalse(watchdog.isCircuitBreakerTripped("Vault"));
        assertEquals(0, watchdog.metrics().activeCircuitBreakers());
    }

    @Test
    public void testLaggyPluginWarning() {
        AgcPluginWatchdog watchdog = AgcPluginWatchdog.get();

        // 8 milliseconds (> 5ms threshold) -> warning issued, but circuit breaker not tripped
        boolean healthy = watchdog.recordExecution("HeavyPlugin", 8_000_000L);
        assertTrue(healthy);
        assertEquals(1, watchdog.metrics().warningsIssued());
        assertFalse(watchdog.isCircuitBreakerTripped("HeavyPlugin"));
    }

    @Test
    public void testCircuitBreakerTripAndReset() {
        AgcPluginWatchdog watchdog = AgcPluginWatchdog.get();

        // 150 milliseconds (> 100ms threshold) -> circuit breaker trips!
        boolean healthy = watchdog.recordExecution("RoguePlugin", 150_000_000L);
        assertFalse(healthy);
        assertTrue(watchdog.isCircuitBreakerTripped("RoguePlugin"));
        assertEquals(1, watchdog.metrics().activeCircuitBreakers());

        // Subsequent execution is blocked
        assertFalse(watchdog.recordExecution("RoguePlugin", 10_000L));

        // Reset
        watchdog.resetCircuitBreaker("RoguePlugin");
        assertFalse(watchdog.isCircuitBreakerTripped("RoguePlugin"));
        assertTrue(watchdog.recordExecution("RoguePlugin", 10_000L));
    }

    @Test
    public void testReportRendering() {
        AgcPluginWatchdog watchdog = AgcPluginWatchdog.get();
        watchdog.recordExecution("CoreProtect", 2_000_000L);
        watchdog.recordExecution("EssentialsX", 1_500_000L);

        String report = watchdog.generatePluginReport();
        assertNotNull(report);
        assertTrue(report.contains("CoreProtect"));
        assertTrue(report.contains("EssentialsX"));
    }
}
