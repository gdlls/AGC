package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcChaosFaultInjectorTest {

    @Test
    void testStmContentionChaosResilience() {
        // Run 8 concurrent threads hammering single block position with 200 mutations each (1,600 total)
        final AgcChaosFaultInjector.ChaosReport report =
            AgcChaosFaultInjector.get().runStmContentionChaos(8, 200);

        assertNotNull(report);
        assertTrue(report.passed(), "Chaos fault injection must pass with zero data corruptions");
        assertEquals(0, report.dataCorruptions());
        assertEquals(1600, report.faultsInjected());
        assertEquals(1600, report.faultsRecovered());
        assertTrue(report.successfulCommits() > 0);

        System.out.println("Chaos Injected: " + report.faultsInjected() + ", Recovered: " + report.faultsRecovered());
    }
}
