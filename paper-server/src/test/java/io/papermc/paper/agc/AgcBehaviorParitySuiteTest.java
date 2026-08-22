package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcBehaviorParitySuite}.
 */
class AgcBehaviorParitySuiteTest {

    @BeforeEach
    @AfterEach
    void resetSuite() {
        AgcBehaviorParitySuite.get().resetMetrics();
    }

    @Test
    void redstoneRequiresStrictOrderPreservation() {
        final var suite = AgcBehaviorParitySuite.get();

        // Strict order match -> Pass
        assertTrue(suite.verifyTransition(AgcBehaviorParitySuite.ParityCategory.REDSTONE_DETERMINISM, true, true));

        // Order deviated -> Fail (Strict order is mandatory for redstone)
        assertFalse(suite.verifyTransition(AgcBehaviorParitySuite.ParityCategory.REDSTONE_DETERMINISM, false, true));

        final var m = suite.metrics();
        assertEquals(1, m.violationsDetected());
        assertFalse(m.isFullyCompliant());
    }

    @Test
    void entityPhysicsAllowsCausalEquivalence() {
        final var suite = AgcBehaviorParitySuite.get();

        // Deviation in execution order but causally equivalent final state -> Pass
        assertTrue(suite.verifyTransition(AgcBehaviorParitySuite.ParityCategory.ENTITY_PHYSICS, false, true));

        // State divergence -> Fail
        assertFalse(suite.verifyTransition(AgcBehaviorParitySuite.ParityCategory.ENTITY_PHYSICS, false, false));

        final var m = suite.metrics();
        assertEquals(1, m.deviationsAllowed());
        assertEquals(1, m.violationsDetected());
    }
}
