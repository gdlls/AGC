package io.papermc.paper.agc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcPacketBroadcastDeduplicator}.
 */
class AgcPacketBroadcastDeduplicatorTest {

    @BeforeEach
    @AfterEach
    void resetMetrics() {
        AgcPacketBroadcastDeduplicator.get().resetMetrics();
    }

    @Test
    void broadcastZeroCopyWithMultipleRecipients() {
        final byte[] raw = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        final ByteBuf buf = Unpooled.wrappedBuffer(raw);
        final List<String> clients = List.of("client1", "client2", "client3", "client4", "client5");
        final List<String> received = new ArrayList<>();

        AgcPacketBroadcastDeduplicator.get().broadcastZeroCopy(buf, clients, received::add);

        assertEquals(5, received.size());
        final var m = AgcPacketBroadcastDeduplicator.get().metrics();
        assertEquals(1, m.broadcasts());
        assertEquals(5, m.recipientsServed());
        assertEquals(4, m.serializationsSaved()); // 5 - 1 = 4 serializations saved
        assertEquals(20, m.bytesDispatched()); // 4 bytes * 5 clients = 20 bytes
    }

    @Test
    void broadcastSingleSerializationOnlyRunsProducerOnce() {
        final AtomicInteger producerCalls = new AtomicInteger(0);
        final List<String> clients = List.of("c1", "c2", "c3", "c4");
        final List<String> delivered = new ArrayList<>();

        AgcPacketBroadcastDeduplicator.get().broadcastSingleSerialization(
            () -> {
                producerCalls.incrementAndGet();
                return "packet-payload";
            },
            clients,
            (c, payload) -> delivered.add(c + ":" + payload)
        );

        assertEquals(1, producerCalls.get(), "Producer must only be evaluated once for all recipients");
        assertEquals(4, delivered.size());
        assertEquals(3, AgcPacketBroadcastDeduplicator.get().metrics().serializationsSaved());
    }

    @Test
    void emptyRecipientsIsNoOp() {
        final ByteBuf buf = Unpooled.wrappedBuffer(new byte[] { 1 });
        AgcPacketBroadcastDeduplicator.get().broadcastZeroCopy(buf, List.of(), c -> {});
        assertEquals(0, AgcPacketBroadcastDeduplicator.get().metrics().broadcasts());
    }
}
