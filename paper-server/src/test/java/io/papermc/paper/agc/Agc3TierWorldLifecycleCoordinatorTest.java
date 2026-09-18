package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class Agc3TierWorldLifecycleCoordinatorTest {

    @BeforeEach
    void setUp() {
        Agc3TierWorldLifecycleCoordinator.get().clear();
    }

    @Test
    void testHotWarmColdLifecycleTransitions() {
        final String world = "minigame_arena_01";
        Agc3TierWorldLifecycleCoordinator.get().registerWorld(world);

        // 1. Initial State with 1 player -> HOT
        Agc3TierWorldLifecycleCoordinator.LifecycleState state =
            Agc3TierWorldLifecycleCoordinator.get().evaluateWorldState(world, 1, 1);
        assertEquals(Agc3TierWorldLifecycleCoordinator.LifecycleState.HOT, state);

        // 2. 0 players for 6 ticks -> transitions to WARM
        for (int i = 0; i < 6; i++) {
            state = Agc3TierWorldLifecycleCoordinator.get().evaluateWorldState(world, 0, 2 + i);
        }
        assertEquals(Agc3TierWorldLifecycleCoordinator.LifecycleState.WARM, state);

        // 3. Player enters -> 0ms Instant Wakeup to HOT
        state = Agc3TierWorldLifecycleCoordinator.get().evaluateWorldState(world, 1, 10);
        assertEquals(Agc3TierWorldLifecycleCoordinator.LifecycleState.HOT, state);
        assertEquals(1, Agc3TierWorldLifecycleCoordinator.get().metrics().instantWakeups());

        // 4. Long idle -> transitions to COLD
        for (int i = 0; i < Agc3TierWorldLifecycleCoordinator.COLD_IDLE_TICKS_THRESHOLD + 10; i++) {
            state = Agc3TierWorldLifecycleCoordinator.get().evaluateWorldState(world, 0, 100 + i);
        }
        assertEquals(Agc3TierWorldLifecycleCoordinator.LifecycleState.COLD, state);
        assertEquals(1, Agc3TierWorldLifecycleCoordinator.get().metrics().coldEvictions());
    }
}
