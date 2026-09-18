package io.papermc.paper.agc.network;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcBroadcastOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcBroadcastOptimizer.get().clear();
    }

    @Test
    public void testLodTierResolution() {
        AgcBroadcastOptimizer optimizer = AgcBroadcastOptimizer.get();

        // 10 blocks distance
        assertEquals(AgcBroadcastOptimizer.PacketLodTier.TIER_0_FULL, optimizer.resolveLodTier(100.0));
        // 30 blocks distance
        assertEquals(AgcBroadcastOptimizer.PacketLodTier.TIER_1_MEDIUM, optimizer.resolveLodTier(900.0));
        // 60 blocks distance
        assertEquals(AgcBroadcastOptimizer.PacketLodTier.TIER_2_FAR, optimizer.resolveLodTier(3600.0));
        // 120 blocks distance
        assertEquals(AgcBroadcastOptimizer.PacketLodTier.TIER_3_VERY_FAR, optimizer.resolveLodTier(14400.0));
    }

    @Test
    public void testMetadataCadenceFiltering() {
        AgcBroadcastOptimizer optimizer = AgcBroadcastOptimizer.get();

        // Tier 0 sends every tick
        assertTrue(optimizer.shouldSendMetadata(AgcBroadcastOptimizer.PacketLodTier.TIER_0_FULL, 1));
        assertTrue(optimizer.shouldSendMetadata(AgcBroadcastOptimizer.PacketLodTier.TIER_0_FULL, 2));

        // Tier 1 sends every 2 ticks
        assertTrue(optimizer.shouldSendMetadata(AgcBroadcastOptimizer.PacketLodTier.TIER_1_MEDIUM, 2));
        assertFalse(optimizer.shouldSendMetadata(AgcBroadcastOptimizer.PacketLodTier.TIER_1_MEDIUM, 3));

        // Tier 2 sends every 4 ticks
        assertTrue(optimizer.shouldSendMetadata(AgcBroadcastOptimizer.PacketLodTier.TIER_2_FAR, 4));
        assertFalse(optimizer.shouldSendMetadata(AgcBroadcastOptimizer.PacketLodTier.TIER_2_FAR, 5));

        // Tier 3 sends every 8 ticks
        assertTrue(optimizer.shouldSendMetadata(AgcBroadcastOptimizer.PacketLodTier.TIER_3_VERY_FAR, 8));
        assertFalse(optimizer.shouldSendMetadata(AgcBroadcastOptimizer.PacketLodTier.TIER_3_VERY_FAR, 7));
    }

    @Test
    public void testChunkBroadcastFanOut() {
        AgcBroadcastOptimizer optimizer = AgcBroadcastOptimizer.get();
        String player1 = "Player1";
        String player2 = "Player2";

        optimizer.registerChunkViewer(10, 20, player1);
        optimizer.registerChunkViewer(10, 20, player2);

        List<String> received = new ArrayList<>();
        optimizer.broadcastToChunkViewers(10, 20, Unpooled.wrappedBuffer(new byte[]{1, 2, 3}), (String player) -> {
            received.add(player);
        });

        assertEquals(2, received.size());
        assertTrue(received.contains(player1));
        assertTrue(received.contains(player2));

        optimizer.unregisterChunkViewer(10, 20, player1);
        received.clear();
        optimizer.broadcastToChunkViewers(10, 20, Unpooled.wrappedBuffer(new byte[]{1, 2, 3}), (String player) -> {
            received.add(player);
        });
        assertEquals(1, received.size());
        assertTrue(received.contains(player2));
    }
}
