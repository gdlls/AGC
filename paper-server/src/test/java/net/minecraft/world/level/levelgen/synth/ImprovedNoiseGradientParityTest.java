package net.minecraft.world.level.levelgen.synth;

import io.papermc.paper.agc.AgcCapabilityMatrix;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the guarantee that made it safe to hoist the AGC noise gate out of
 * {@link ImprovedNoise#gradDot}: the "branchless Perlin" fast gradient is
 * <b>bit-identical</b> to vanilla's {@code SimplexNoise.dot(GRADIENT[hash & 15], x, y, z)}
 * expansion for every one of the 16 table entries.
 *
 * <p>If any of these tests fail, AGC worldgen would diverge from vanilla and the
 * {@code FAST_NOISE_GENERATOR} feature must not be enabled at all.</p>
 */
public class ImprovedNoiseGradientParityTest {

    private static final int GRID = 40;

    private static final double[] COMPONENTS = {
        0.0, 0.125, 0.25, 0.5, 0.75, 1.0, -0.0, -0.125, -0.25, -0.5, -0.75, -1.0,
        1.0E-7, -1.0E-7, 12345.6789, -98765.4321, 2.5E-3, 1.0E6
    };

    @Test
    void everyHashMatchesTheVanillaGradientTable() {
        for (int hash = 0; hash < 1024; hash++) {
            final int index = hash & 15;
            for (final double x : COMPONENTS) {
                for (final double y : COMPONENTS) {
                    for (final double z : COMPONENTS) {
                        final double vanilla = SimplexNoise.dot(SimplexNoise.GRADIENT[index], x, y, z);
                        final double fast = ImprovedNoise.agc$gradDot(hash, x, y, z, true);
                        assertEquals(vanilla, fast, 0.0,
                            "gradient mismatch at hash=" + hash + " x=" + x + " y=" + y + " z=" + z);
                    }
                }
            }
        }
    }

    @Test
    void fuzzedCoordinatesMatchTheVanillaGradientTable() {
        final Random random = new Random(0x5EEDL);
        final double[] coords = new double[3];
        for (int i = 0; i < 200_000; i++) {
            for (int c = 0; c < 3; c++) {
                coords[c] = (random.nextDouble() - 0.5) * 4096.0;
            }
            final int hash = random.nextInt() & 0xFFFF;
            final double vanilla = SimplexNoise.dot(SimplexNoise.GRADIENT[hash & 15], coords[0], coords[1], coords[2]);
            final double fast = ImprovedNoise.agc$gradDot(hash, coords[0], coords[1], coords[2], true);
            assertEquals(vanilla, fast, 0.0,
                "gradient mismatch at hash=" + hash + " coords=" + coords[0] + "," + coords[1] + "," + coords[2]);
        }
    }

    /**
     * The disabled branch must still be the untouched vanilla computation, so that
     * {@code AGC mode VANILLA} produces exactly upstream output.
     */
    @Test
    void disabledBranchIsTheVanillaComputation() {
        assertEquals(
            SimplexNoise.dot(SimplexNoise.GRADIENT[7], 0.25, -0.5, 0.75),
            ImprovedNoise.agc$gradDot(7, 0.25, -0.5, 0.75, false),
            0.0);
        assertEquals(
            ImprovedNoise.gradDot(3, 0.1, 0.2, 0.3),
            ImprovedNoise.agc$gradDot(3, 0.1, 0.2, 0.3,
                AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.FAST_NOISE_GENERATOR)),
            0.0,
            "the public entry point must resolve through the same gate as every in-class call site");
    }

    /**
     * End-to-end proof through the real public noise API rather than through the gradient
     * helper: one {@link ImprovedNoise} instance is sampled twice - once with the AGC engine
     * gated off (vanilla gradient table) and once gated on (fast gradient) - and every single
     * sample must be byte-for-byte identical, including the derivative path used by
     * density-function splines.
     */
    @Test
    void wholeNoiseFunctionIsBitIdenticalAcrossTheGate() {
        AgcCapabilityMatrix.clearRuntimeOverrides();
        final ImprovedNoise noise = new ImprovedNoise(RandomSource.create(0xA6C0FFEEL));

        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.FAST_NOISE_GENERATOR, false);
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE, false);
        final double[] vanillaNoise = sampleNoise(noise);
        final double[] vanillaDerivative = sampleDerivative(noise);

        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.FAST_NOISE_GENERATOR, true);
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE, true);
        final double[] fastNoise = sampleNoise(noise);
        final double[] fastDerivative = sampleDerivative(noise);

        assertTrue(Arrays.stream(vanillaNoise).distinct().count() > 1000,
            "sanity: the sampled grid must actually vary");
        assertArrayEquals(vanillaNoise, fastNoise, "ImprovedNoise.noise() diverged across the gate");
        assertArrayEquals(vanillaDerivative, fastDerivative,
            "ImprovedNoise.noiseWithDerivative() diverged across the gate");
    }

    private static double[] sampleNoise(final ImprovedNoise noise) {
        final double[] out = new double[GRID * GRID * 3];
        int i = 0;
        for (int x = 0; x < GRID; x++) {
            for (int z = 0; z < GRID; z++) {
                out[i++] = noise.noise(x * 0.37, (x * 3 - z * 7) * 0.91, z * 0.41);
                out[i++] = noise.noise(x * 0.017, z * 5 * 0.13, x * 0.29);
                out[i++] = noise.noise(x * 2.5, z * 2.5, (x + z) * 0.5);
            }
        }
        return out;
    }

    private static double[] sampleDerivative(final ImprovedNoise noise) {
        final double[] out = new double[GRID * GRID];
        final double[] derivative = new double[3];
        int i = 0;
        for (int x = 0; x < GRID; x++) {
            for (int z = 0; z < GRID; z++) {
                Arrays.fill(derivative, 0.0);
                noise.noiseWithDerivative(x * 0.37, (x * 3 - z * 7) * 0.91, z * 0.41, derivative);
                out[i++] = derivative[0] + derivative[1] * 3.0 + derivative[2] * 7.0;
            }
        }
        return out;
    }
}
