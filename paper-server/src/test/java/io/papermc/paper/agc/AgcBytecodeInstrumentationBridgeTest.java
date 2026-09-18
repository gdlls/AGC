package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class AgcBytecodeInstrumentationBridgeTest {

    @BeforeEach
    void setUp() {
        AgcBytecodeInstrumentationBridge.get().resetMetrics();
        AgcPluginVirtualizer.get().resetMetrics();
        AgcPluginSafetyGuard.get().resetMetrics();
    }

    @Test
    void testRouteSchedulerTaskFastPath() {
        final AtomicBoolean executed = new AtomicBoolean(false);

        AgcPluginVirtualizer.get().runInVirtualPrimary(() -> {
            AgcBytecodeInstrumentationBridge.get().routeSchedulerTask("TestPlugin", () -> {
                executed.set(true);
            });
        });

        assertTrue(executed.get());
        final AgcBytecodeInstrumentationBridge.BridgeMetrics metrics = AgcBytecodeInstrumentationBridge.get().metrics();
        assertEquals(1, metrics.schedulerCallsRouted());
        assertEquals(1, metrics.directFastPathExecutions());
    }

    @Test
    void testRouteEventExecutionProvidesVirtualPrimary() {
        final AtomicBoolean validated = new AtomicBoolean(false);

        final Thread dummyPrimary = new Thread(() -> {}, "dummy-primary");
        AgcPluginSafetyGuard.get().bindPrimaryThread(dummyPrimary);

        AgcBytecodeInstrumentationBridge.get().routeEventExecution("world", 100L, () -> {
            assertTrue(AgcPluginVirtualizer.isVirtualPrimary());
            assertTrue(AgcPluginSafetyGuard.get().isPrimaryThread());
            validated.set(true);
        });

        assertTrue(validated.get());
    }
}
