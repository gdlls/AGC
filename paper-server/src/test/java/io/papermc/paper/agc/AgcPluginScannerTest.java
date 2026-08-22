package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcPluginScanner}.
 */
class AgcPluginScannerTest {

    @BeforeEach
    @AfterEach
    void resetScanner() {
        AgcPluginScanner.get().clear();
    }

    @Test
    void registerAndQueryPluginConcurrencyProfile() {
        final var scanner = AgcPluginScanner.get();
        scanner.registerPluginAnalysis("LuckPerms", AgcPluginScanner.ConcurrencyProfile.FULLY_PARALLEL_SAFE, "Thread-safe permissions");
        scanner.registerPluginAnalysis("Multiverse-Core", AgcPluginScanner.ConcurrencyProfile.CROSS_WORLD_MUTATING, "World management & cross-teleports");

        assertEquals(AgcPluginScanner.ConcurrencyProfile.FULLY_PARALLEL_SAFE, scanner.getProfile("LuckPerms"));
        assertEquals(AgcPluginScanner.ConcurrencyProfile.CROSS_WORLD_MUTATING, scanner.getProfile("multiverse-core"));
        assertTrue(scanner.hasCrossWorldMutatingPlugins());

        assertEquals(2, scanner.getAllReports().size());
    }

    @Test
    void unlistedPluginDefaultsToParallelSafe() {
        final var scanner = AgcPluginScanner.get();
        assertEquals(AgcPluginScanner.ConcurrencyProfile.FULLY_PARALLEL_SAFE, scanner.getProfile("UnknownPlugin"));
        assertFalse(scanner.hasCrossWorldMutatingPlugins());
    }
}
