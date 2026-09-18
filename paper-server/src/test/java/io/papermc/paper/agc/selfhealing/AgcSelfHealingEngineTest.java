package io.papermc.paper.agc.selfhealing;

import io.papermc.paper.agc.profiling.AgcMemoryTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcSelfHealingEngineTest {

    @BeforeEach
    public void setup() {
        AgcSelfHealingEngine.get().clear();
    }

    @Test
    public void testOomMitigationTrigger() {
        AgcSelfHealingEngine engine = AgcSelfHealingEngine.get();

        // Normal memory -> 0 stages executed
        int normalStages = engine.checkAndMitigateOom(AgcMemoryTracker.get());
        assertEquals(0, normalStages);
        assertFalse(engine.metrics().isOomEmergencyActive());
    }

    @Test
    public void testWatchdogStallDump() {
        AgcSelfHealingEngine engine = AgcSelfHealingEngine.get();

        // Short pause -> no dump
        assertNull(engine.handleThreadStall(5000L));

        // 12 second stall -> dump generated
        String dump = engine.handleThreadStall(12000L);
        assertNotNull(dump);
        assertTrue(dump.contains("AGC WATCHDOG STALL THREAD DUMP"));
        assertEquals(1, engine.metrics().threadStallsDetected());
    }
}
