package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgcFastRedstoneSimdEngineTest {

    @BeforeEach
    void setUp() {
        AgcFastRedstoneSimdEngine.get().clearMetrics();
    }

    @Test
    void testLinearRedstoneWireDropoff() {
        // Line of 16 redstone dust wires: Node 0 is power source 15, nodes 1..15 are initial 0
        final int nodeCount = 16;
        final long[] positions = new long[nodeCount];
        final byte[] powers = new byte[nodeCount];
        final int[][] adjacencies = new int[nodeCount][];

        powers[0] = 15;
        for (int i = 0; i < nodeCount; i++) {
            positions[i] = (long) i;
            if (i == 0) {
                adjacencies[i] = new int[] { 1 };
            } else if (i == nodeCount - 1) {
                adjacencies[i] = new int[] { nodeCount - 2 };
            } else {
                adjacencies[i] = new int[] { i - 1, i + 1 };
            }
        }

        final byte[] finalPowers = new byte[nodeCount];
        finalPowers[0] = 15;

        final int changes = AgcFastRedstoneSimdEngine.get().propagateNetwork(
            positions,
            powers,
            adjacencies,
            (idx, pwr) -> finalPowers[idx] = pwr
        );

        assertTrue(changes > 0);
        assertEquals(15, finalPowers[0]);
        assertEquals(14, finalPowers[1]);
        assertEquals(13, finalPowers[2]);
        assertEquals(0, finalPowers[15]);

        final AgcFastRedstoneSimdEngine.RedstoneMetrics metrics = AgcFastRedstoneSimdEngine.get().metrics();
        assertEquals(1, metrics.totalNetworksEvaluated());
        assertEquals(nodeCount, metrics.totalNodesPropagated());
    }
}
