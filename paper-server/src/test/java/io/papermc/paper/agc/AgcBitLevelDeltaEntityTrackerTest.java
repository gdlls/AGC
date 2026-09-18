package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class AgcBitLevelDeltaEntityTrackerTest {

    @BeforeEach
    void setUp() {
        AgcBitLevelDeltaEntityTracker.get().clearMetrics();
    }

    @Test
    void testDeltaTrackingCompressesPayload() {
        final AgcBitLevelDeltaEntityTracker.EntityState previous = new AgcBitLevelDeltaEntityTracker.EntityState(
            100.0f, 64.0f, -50.0f,
            90, 0,
            0.0f, 0.0f, 0.0f,
            20.0f,
            (byte) 0
        );

        // State changes: only position X and Z slightly shifted
        final AgcBitLevelDeltaEntityTracker.EntityState current = new AgcBitLevelDeltaEntityTracker.EntityState(
            100.5f, 64.0f, -49.8f,
            90, 0,
            0.0f, 0.0f, 0.0f,
            20.0f,
            (byte) 0
        );

        final long mask = AgcBitLevelDeltaEntityTracker.get().computeDirtyMask(current, previous);
        assertEquals(AgcBitLevelDeltaEntityTracker.MASK_POS_X | AgcBitLevelDeltaEntityTracker.MASK_POS_Z, mask);

        final ByteBuffer buffer = ByteBuffer.allocate(64);
        final int bytesWritten = AgcBitLevelDeltaEntityTracker.get().encodeDelta(101, current, mask, buffer);

        // Header (4+1=5 bytes) + posX (4 bytes) + posZ (4 bytes) = 13 bytes vs baseline 48 bytes
        assertEquals(13, bytesWritten);

        final AgcBitLevelDeltaEntityTracker.DeltaMetrics metrics = AgcBitLevelDeltaEntityTracker.get().metrics();
        assertEquals(1, metrics.totalDeltasComputed());
        assertTrue(metrics.bandwidthSavingsPercent() > 60.0, "Delta encoding should achieve >60% bandwidth savings");

        System.out.println("Entity Delta Bandwidth Savings: " + String.format("%.2f", metrics.bandwidthSavingsPercent()) + "%");
    }
}
