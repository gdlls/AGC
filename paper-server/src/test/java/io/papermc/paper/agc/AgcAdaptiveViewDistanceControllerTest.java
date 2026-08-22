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
    void highDensityScalesDownViewDistance() {
        final var controller = AgcAdaptiveViewDistanceController.get();

        // 500 players, healthy MSPT 20ms -> clamped to max 6
        final int dist500 = controller.calculateOptimalDistance(20.0, 500, 4, 12);
        assertTrue(dist500 <= 6);
        assertTrue(dist500 >= 4);

        // 50 players, healthy MSPT 15ms -> can reach up to 12
        final int dist50 = controller.calculateOptimalDistance(15.0, 50, 4, 12);
        assertTrue(dist50 >= 10);
    }

    @Test
    void highMsptScalesDownViewDistanceToProtectTps() {
        final var controller = AgcAdaptiveViewDistanceController.get();

        // Severe lag (MSPT = 48ms) -> decreases view distance
        final int distLag = controller.calculateOptimalDistance(48.0, 100, 4, 12);
        assertTrue(distLag <= 8);
    }

    @Test
    void clampsWithinMinMaxBounds() {
        final var controller = AgcAdaptiveViewDistanceController.get();
        final int dist = controller.calculateOptimalDistance(100.0, 1000, 5, 8);

        assertTrue(dist >= 5);
        assertTrue(dist <= 8);
    }
}
