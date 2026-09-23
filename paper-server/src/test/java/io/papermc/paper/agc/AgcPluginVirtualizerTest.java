package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgcPluginVirtualizerTest {

    @BeforeEach
    void setUp() {
        AgcPluginVirtualizer.get().resetMetrics();
        AgcPluginSafetyGuard.get().resetMetrics();
        AgcPluginSafetyGuard.get().bindPrimaryThread(null);
    }

    @Test
    void testContextBookkeepingWithoutThreadIdentityFaking() {
        // Explicitly bind a dummy thread as primary to simulate a non-primary worker thread
        final Thread dummyPrimary = new Thread(() -> {}, "dummy-primary");
        AgcPluginSafetyGuard.get().bindPrimaryThread(dummyPrimary);

        assertFalse(AgcPluginVirtualizer.isVirtualPrimary());
        assertFalse(AgcPluginSafetyGuard.get().isPrimaryThread());

        final AtomicBoolean ranInContext = new AtomicBoolean(false);
        AgcPluginVirtualizer.get().runInContext("world_nether", 12345L, () -> {
            // The context records WHERE work happens; it must never claim the work is on the
            // primary thread (that lie let worker threads mutate world state unnoticed).
            assertFalse(AgcPluginVirtualizer.isVirtualPrimary(),
                "virtual contexts must not publish a fake primary identity");
            assertFalse(AgcPluginSafetyGuard.get().isPrimaryThread(),
                "an off-primary thread must stay off-primary inside a context");
            assertEquals("world_nether", AgcPluginVirtualizer.currentContext().worldId());
            assertEquals(12345L, AgcPluginVirtualizer.currentContext().regionKey());
            ranInContext.set(true);
        });

        assertTrue(ranInContext.get());
        assertFalse(AgcPluginVirtualizer.isVirtualPrimary());
        assertFalse(AgcPluginSafetyGuard.get().isPrimaryThread());
    }

    @Test
    void testNestedContextScopes() {
        final Thread dummyPrimary = new Thread(() -> {}, "dummy-primary");
        AgcPluginSafetyGuard.get().bindPrimaryThread(dummyPrimary);

        try (final AgcPluginVirtualizer.ContextScope scope1 = AgcPluginVirtualizer.get().enterContext("world", 100L)) {
            assertEquals("world", AgcPluginVirtualizer.currentContext().worldId());

            try (final AgcPluginVirtualizer.ContextScope scope2 = AgcPluginVirtualizer.get().enterContext("world_the_end", 200L)) {
                assertEquals("world_the_end", AgcPluginVirtualizer.currentContext().worldId());
                assertEquals(200L, AgcPluginVirtualizer.currentContext().regionKey());
            }

            assertEquals("world", AgcPluginVirtualizer.currentContext().worldId());
        }

        assertNull(AgcPluginVirtualizer.currentContext());
    }

    @Test
    void testSupplyInContext() {
        final Integer result = AgcPluginVirtualizer.get().supplyInContext("world", 42L, () -> {
            assertFalse(AgcPluginVirtualizer.isVirtualPrimary());
            assertEquals("world", AgcPluginVirtualizer.currentContext().worldId());
            return 999;
        });

        assertEquals(999, result);
    }
}
