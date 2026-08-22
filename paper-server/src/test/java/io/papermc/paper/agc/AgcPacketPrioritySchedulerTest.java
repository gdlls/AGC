package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcPacketPriorityScheduler}.
 */
class AgcPacketPrioritySchedulerTest {

    @BeforeEach
    @AfterEach
    void resetMetrics() {
        AgcPacketPriorityScheduler.get().resetMetrics();
    }

    @Test
    void classifiesPacketNamesCorrectly() {
        assertEquals(AgcPacketPriorityScheduler.Priority.CRITICAL,
            AgcPacketPriorityScheduler.get().classify("ClientboundKeepAlivePacket"));
        assertEquals(AgcPacketPriorityScheduler.Priority.CRITICAL,
            AgcPacketPriorityScheduler.get().classify("ClientboundPingPacket"));

        assertEquals(AgcPacketPriorityScheduler.Priority.INTERACTIVE,
            AgcPacketPriorityScheduler.get().classify("ClientboundSystemChatPacket"));
        assertEquals(AgcPacketPriorityScheduler.Priority.INTERACTIVE,
            AgcPacketPriorityScheduler.get().classify("ClientboundDamageEventPacket"));

        assertEquals(AgcPacketPriorityScheduler.Priority.BULK_DATA,
            AgcPacketPriorityScheduler.get().classify("ClientboundLevelChunkWithLightPacket"));
        assertEquals(AgcPacketPriorityScheduler.Priority.BULK_DATA,
            AgcPacketPriorityScheduler.get().classify("ClientboundMapItemDataPacket"));

        assertEquals(AgcPacketPriorityScheduler.Priority.STANDARD,
            AgcPacketPriorityScheduler.get().classify("ClientboundSoundEntityPacket"));
    }

    @Test
    void recordPacketTracksTrafficByPriority() {
        AgcPacketPriorityScheduler.get().recordPacket(AgcPacketPriorityScheduler.Priority.CRITICAL, 64);
        AgcPacketPriorityScheduler.get().recordPacket(AgcPacketPriorityScheduler.Priority.INTERACTIVE, 256);
        AgcPacketPriorityScheduler.get().recordPacket(AgcPacketPriorityScheduler.Priority.BULK_DATA, 16384);

        final var m = AgcPacketPriorityScheduler.get().metrics();
        assertEquals(1, m.criticalPackets());
        assertEquals(64, m.criticalBytes());

        assertEquals(1, m.interactivePackets());
        assertEquals(256, m.interactiveBytes());

        assertEquals(1, m.bulkPackets());
        assertEquals(16384, m.bulkBytes());

        assertEquals(3, m.totalPackets());
        assertEquals(64 + 256 + 16384, m.totalBytes());
    }

    @Test
    void priorityFlushRequirementsAreCorrect() {
        assertTrue(AgcPacketPriorityScheduler.Priority.CRITICAL.requiresImmediateFlush());
        assertTrue(AgcPacketPriorityScheduler.Priority.INTERACTIVE.requiresImmediateFlush());
        assertFalse(AgcPacketPriorityScheduler.Priority.STANDARD.requiresImmediateFlush());
        assertFalse(AgcPacketPriorityScheduler.Priority.BULK_DATA.requiresImmediateFlush());
    }
}
