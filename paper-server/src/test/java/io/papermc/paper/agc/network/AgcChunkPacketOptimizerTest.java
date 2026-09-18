package io.papermc.paper.agc.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcChunkPacketOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcChunkPacketOptimizer.get().clear();
    }

    @Test
    public void testChunkPacketCaching() {
        AgcChunkPacketOptimizer optimizer = AgcChunkPacketOptimizer.get();
        AtomicInteger encodeCalls = new AtomicInteger(0);

        byte[] original = new byte[]{1, 2, 3, 4, 5};

        // First call should invoke supplier
        byte[] res1 = optimizer.getOrEncodeChunkPacket("world", 10, 20, () -> {
            encodeCalls.incrementAndGet();
            return original;
        });
        assertEquals(1, encodeCalls.get());
        assertArrayEquals(original, res1);

        // Second call for same chunk should hit cache
        byte[] res2 = optimizer.getOrEncodeChunkPacket("world", 10, 20, () -> {
            encodeCalls.incrementAndGet();
            return new byte[]{9, 9, 9};
        });
        assertEquals(1, encodeCalls.get());
        assertArrayEquals(original, res2);

        assertEquals(1, optimizer.metrics().cacheHits());
        assertEquals(1, optimizer.metrics().cacheMisses());

        // Invalidate chunk
        optimizer.invalidate("world", 10, 20);

        // Third call should re-encode
        byte[] updated = new byte[]{9, 9, 9};
        byte[] res3 = optimizer.getOrEncodeChunkPacket("world", 10, 20, () -> {
            encodeCalls.incrementAndGet();
            return updated;
        });
        assertEquals(2, encodeCalls.get());
        assertArrayEquals(updated, res3);
    }

    @Test
    public void testChunkTransmissionPriority() {
        AgcChunkPacketOptimizer optimizer = AgcChunkPacketOptimizer.get();

        // Player at (0, 0) facing +Z (yaw = 0)
        // Look vector: (0, 1) -> +Z is directly ahead
        float yawFacingSouth = 0.0f; // in Minecraft yaw 0 is South (+Z)

        // Chunk directly ahead: (0, 5)
        double priorityAhead = optimizer.computeChunkPriority(0, 0, yawFacingSouth, 0, 5);

        // Chunk directly behind: (0, -5)
        double priorityBehind = optimizer.computeChunkPriority(0, 0, yawFacingSouth, 0, -5);

        // Player's own chunk
        double priorityCurrent = optimizer.computeChunkPriority(0, 0, yawFacingSouth, 0, 0);

        assertTrue(priorityCurrent > priorityAhead, "Current chunk must have highest priority");
        assertTrue(priorityAhead > priorityBehind, "Chunk directly in front must have higher priority than chunk behind");
    }
}
