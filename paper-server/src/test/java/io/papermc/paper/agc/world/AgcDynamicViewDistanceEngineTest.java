package io.papermc.paper.agc.world;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcDynamicViewDistanceEngineTest {

    @BeforeEach
    public void setup() {
        AgcDynamicViewDistanceEngine.get().clear();
    }

    @Test
    public void testIndependentWorldViewDistanceScaling() {
        AgcDynamicViewDistanceEngine engine = AgcDynamicViewDistanceEngine.get();

        // High load world: 400 players, 55ms MSPT -> view distance steps down gradually
        var highLoadProfile = engine.updateWorldDistance("world_hub", 400, 55.0, 10, 8);
        assertNotNull(highLoadProfile);
        assertEquals(9, highLoadProfile.viewDistance()); // Stepped down from 10 to 9
        assertEquals(7, highLoadProfile.simulationDistance()); // Stepped down from 8 to 7

        // Another step down
        engine.updateWorldDistance("world_hub", 400, 55.0, 10, 8);
        assertEquals(8, highLoadProfile.viewDistance());

        // Low load world: 5 players, 15ms MSPT -> stays at configured ceiling
        var lowLoadProfile = engine.updateWorldDistance("world_creative", 5, 15.0, 12, 10);
        assertEquals(12, lowLoadProfile.viewDistance());
        assertEquals(10, lowLoadProfile.simulationDistance());
    }

    @Test
    public void testMinimumDistanceFloors() {
        AgcDynamicViewDistanceEngine engine = AgcDynamicViewDistanceEngine.get();

        // Simulate 20 passes under extreme lag
        for (int i = 0; i < 20; i++) {
            engine.updateWorldDistance("world_lag", 1000, 70.0, 10, 8);
        }

        var profile = engine.getDistanceProfile("world_lag");
        assertNotNull(profile);
        assertTrue(profile.viewDistance() >= AgcDynamicViewDistanceEngine.MIN_VIEW_DISTANCE);
        assertTrue(profile.simulationDistance() >= AgcDynamicViewDistanceEngine.MIN_SIM_DISTANCE);
    }
}
