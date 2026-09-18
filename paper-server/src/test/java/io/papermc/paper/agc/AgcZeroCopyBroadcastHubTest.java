package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgcZeroCopyBroadcastHubTest {

    @BeforeEach
    void setUp() {
        AgcZeroCopyBroadcastHub.get().clearMetrics();
    }

    @Test
    void testDenseBroadcastSavesSerializations() {
        // 2,000 subscribers in the same broadcast area
        final int subscriberCount = 2000;
        final List<String> subscribers = new ArrayList<>(subscriberCount);
        for (int i = 0; i < subscriberCount; i++) {
            subscribers.add("client_connection_" + i);
        }

        final byte[] packetPayload = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        final AtomicInteger receivedCount = new AtomicInteger();

        final int reached = AgcZeroCopyBroadcastHub.get().broadcast(
            0x28, // Entity Move packet opcode
            packetPayload,
            subscribers,
            (conn, bytes) -> receivedCount.incrementAndGet()
        );

        assertEquals(subscriberCount, reached);
        assertEquals(subscriberCount, receivedCount.get());

        final AgcZeroCopyBroadcastHub.HubMetrics metrics = AgcZeroCopyBroadcastHub.get().metrics();
        assertEquals(1, metrics.totalBroadcasts());
        assertEquals(subscriberCount, metrics.totalSubscribersReached());
        assertEquals(subscriberCount - 1, metrics.serializationsSaved(), "Must save (N-1) redundant serializations");
        assertEquals((long) packetPayload.length * subscriberCount, metrics.totalBytesBroadcasted());
    }
}
