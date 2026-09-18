package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcAutonomousPidGovernorTest {

    @BeforeEach
    void setUp() {
        AgcAutonomousPidGovernor.get().clearMetrics();
    }

    @Test
    void testPidFeedbackAndCongestionMitigation() {
        // 1. Healthy State (MSPT = 10ms, Mem = 70%) -> View Distance = 8, Load Factor = 1.0
        AgcAutonomousPidGovernor.GovernorAdjustment adj =
            AgcAutonomousPidGovernor.get().update(10.0, 70.0, 500);

        assertEquals(8, adj.recommendedViewDistance());
        assertEquals(1.0, adj.recommendedLoadFactor());

        // 2. High Congestion (MSPT = 45ms, Mem = 10%) -> View Distance throttles down to 4
        adj = AgcAutonomousPidGovernor.get().update(45.0, 10.0, 2000);

        assertEquals(4, adj.recommendedViewDistance());
        assertEquals(0.5, adj.recommendedLoadFactor());
        assertTrue(AgcAutonomousPidGovernor.get().metrics().congestionsMitigated() > 0);

        // 3. Recovery (MSPT drops to 12ms, Mem = 65%) -> Restores towards normal
        for (int i = 0; i < 5; i++) {
            adj = AgcAutonomousPidGovernor.get().update(12.0, 65.0, 1000);
        }

        assertTrue(adj.recommendedViewDistance() >= 6);
    }
}
