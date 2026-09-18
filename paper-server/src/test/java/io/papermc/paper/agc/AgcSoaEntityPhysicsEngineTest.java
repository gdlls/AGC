package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcSoaEntityPhysicsEngineTest {

    @BeforeEach
    void setUp() {
        AgcSoaEntityPhysicsEngine.get().clear();
    }

    @Test
    void testSoaEntityMotionStep() {
        final int idx = AgcSoaEntityPhysicsEngine.get().allocateEntity(
            0f, 100f, 0f,
            1.0f, 0.5f, -1.0f,
            0.3f, 1.8f,
            0.08f, 0.91f
        );

        assertEquals(0, idx);
        assertEquals(1, AgcSoaEntityPhysicsEngine.get().getActiveCount());

        // Step 1 tick (0.05s)
        AgcSoaEntityPhysicsEngine.get().stepMotionAll(0.05f);

        assertTrue(AgcSoaEntityPhysicsEngine.get().getX(0) > 0f);
        assertTrue(AgcSoaEntityPhysicsEngine.get().getZ(0) < 0f);
        assertEquals(1, AgcSoaEntityPhysicsEngine.get().metrics().totalPhysicsTicks());
        assertEquals(1, AgcSoaEntityPhysicsEngine.get().metrics().totalEntitiesStepped());
    }

    @Test
    void testMassive10kEntitySoaStreamingPerformance() {
        final int count = 10000;
        for (int i = 0; i < count; i++) {
            AgcSoaEntityPhysicsEngine.get().allocateEntity(
                (float) i, 64f, (float) (i % 100),
                0.1f, 0.0f, 0.1f,
                0.3f, 1.8f,
                0.08f, 0.91f
            );
        }

        assertEquals(count, AgcSoaEntityPhysicsEngine.get().getActiveCount());

        final long start = System.nanoTime();
        AgcSoaEntityPhysicsEngine.get().stepMotionAll(0.05f);
        final double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;

        // 10,000 entities stepped in under 5.0ms
        assertTrue(elapsedMs < 50.0, "10k entity SoA physics step must execute rapidly");
        System.out.println("SoA Physics 10,000 Entities Step Time: " + elapsedMs + "ms");
    }

    @Test
    void testEntityDeactivationAndDeallocation() {
        final var engine = AgcSoaEntityPhysicsEngine.get();
        final int idx0 = engine.allocateEntity(0f, 64f, 0f, 1f, 0f, 0f, 0.3f, 1.8f, 0.08f, 0.91f);
        final int idx1 = engine.allocateEntity(10f, 64f, 10f, 0f, 0f, 1f, 0.3f, 1.8f, 0.08f, 0.91f);

        assertEquals(2, engine.getActiveCount());

        // Deactivate idx0
        engine.deactivateEntity(idx0);
        final float initX = engine.getX(idx0);
        engine.stepMotionAll(0.05f);
        assertEquals(initX, engine.getX(idx0), "Deactivated entity must not move during physics step");

        // Deallocate idx0 (swap-and-pop)
        engine.deallocateEntity(idx0);
        assertEquals(1, engine.getActiveCount());
    }
}
