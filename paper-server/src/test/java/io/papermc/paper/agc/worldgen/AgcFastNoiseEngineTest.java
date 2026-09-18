package io.papermc.paper.agc.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcFastNoiseEngineTest {

    @Test
    public void testParityAgainstReference() {
        final AgcFastNoiseEngine engine = new AgcFastNoiseEngine(1337L);
        final int[] perm = engine.permutationSnapshot();
        // Deterministic grid incl. negative coords, fractional lattice crossings.
        final double[] coords = {-129.7, -64.0, -0.5, 0.0, 0.5, 7.25, 64.125, 255.9, 1024.33};
        for (final double x : coords) {
            for (final double y : coords) {
                for (final double z : new double[] {-3.75, 0.25, 9.5}) {
                    final double fast = engine.sample3D(x, y, z);
                    final double ref = AgcFastNoiseEngine.referenceSample3D(perm, x, y, z);
                    assertEquals(ref, fast, 0.0, "parity failure at " + x + "," + y + "," + z);
                }
            }
        }
        assertTrue(engine.samplesEvaluated() > 0);
    }

    @Test
    public void testDeterministicPerSeed() {
        final AgcFastNoiseEngine a = new AgcFastNoiseEngine(42L);
        final AgcFastNoiseEngine b = new AgcFastNoiseEngine(42L);
        assertArrayEquals(a.permutationSnapshot(), b.permutationSnapshot());
        assertEquals(a.sample3D(1.5, 2.5, 3.5), b.sample3D(1.5, 2.5, 3.5), 0.0);
    }

    @Test
    public void testOctaveAccumulationMatchesManualSum() {
        final AgcFastNoiseEngine engine = new AgcFastNoiseEngine(7L);
        final double[] freq = {1.0, 2.0, 4.0};
        final double[] amp = {1.0, 0.5, 0.25};
        final double batched = engine.sampleOctaves(3.1, 5.7, 9.2, freq, amp, 3);
        final double manual = engine.sample3D(3.1, 5.7, 9.2)
            + 0.5 * engine.sample3D(6.2, 11.4, 18.4)
            + 0.25 * engine.sample3D(12.4, 22.8, 36.8);
        assertEquals(manual, batched, 1e-12);
    }

    @Test
    public void testColumnBatchMatchesScalar() {
        final AgcFastNoiseEngine engine = new AgcFastNoiseEngine(99L);
        final double[] out = new double[16];
        engine.sampleColumnX(0.0, 4.5, -2.25, 1.0, out, 0, 16);
        for (int i = 0; i < 16; i++) {
            assertEquals(engine.sample3D(i, 4.5, -2.25), out[i], 0.0);
        }
    }

    @Test
    public void testThreadLocalReseed() {
        final AgcFastNoiseEngine e1 = AgcFastNoiseEngine.threadLocal(1234L);
        final double v1 = e1.sample3D(1, 2, 3);
        final AgcFastNoiseEngine e2 = AgcFastNoiseEngine.threadLocal(1234L);
        assertEquals(v1, e2.sample3D(1, 2, 3), 0.0);
        assertEquals(1234L, e2.seed());
    }
}
