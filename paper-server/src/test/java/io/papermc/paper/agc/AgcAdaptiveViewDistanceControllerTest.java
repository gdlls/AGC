package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcAdaptiveViewDistanceController}.
 */
class AgcAdaptiveViewDistanceControllerTest {

    @BeforeEach
    @AfterEach
    void resetController() {
        AgcAdaptiveViewDistanceController.get().resetMetrics();
    }

    @Test
    void healthyPerformanceMaintainsFullConfiguredViewDistance() {
        final var controller = AgcAdaptiveViewDistanceController.get();

        // 500 players, healthy MSPT 15ms -> stays at 100% full view distance (12)
        final int distHealthy500 = controller.calculateOptimalDistance(15.0, 500, 4, 12);
        assertEquals(12, distHealthy500);

        // 50 players, healthy MSPT 10ms -> full 12
        final int distHealthy50 = controller.calculateOptimalDistance(10.0, 50, 4, 12);
        assertEquals(12, distHealthy50);
    }

    @Test
    void highMsptMaintainsFullViewDistanceUnderLosslessPolicy() {
        final var controller = AgcAdaptiveViewDistanceController.get();

        // Under lossless policy: even with severe lag (MSPT = 48ms), view distance remains max to prevent fog pop-in
        final int distLag = controller.calculateOptimalDistance(48.0, 100, 4, 12);
        assertEquals(12, distLag);
    }

    @Test
    void clampsWithinMinMaxBounds() {
        final var controller = AgcAdaptiveViewDistanceController.get();
        final int dist = controller.calculateOptimalDistance(100.0, 1000, 5, 8);

        assertTrue(dist >= 5);
        assertTrue(dist <= 8);
    }
}
