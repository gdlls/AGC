package io.papermc.paper.agc;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcFlushCoalescer}.
 */
class AgcFlushCoalescerTest {

    @BeforeEach
    @AfterEach
    void resetMetrics() {
        AgcFlushCoalescer.get().resetMetrics();
        AgcFlushCoalescer.get().setEnabled(true);
    }

    @Test
    void registerAndFlushCoalescedChannels() {
        final EmbeddedChannel ch1 = new EmbeddedChannel();
        final EmbeddedChannel ch2 = new EmbeddedChannel();

        AgcFlushCoalescer.get().registerPendingFlush(ch1);
        AgcFlushCoalescer.get().registerPendingFlush(ch2);

        assertEquals(2, AgcFlushCoalescer.get().pendingChannelCount());

        final int flushed = AgcFlushCoalescer.get().flushPendingChannels();
        assertEquals(2, flushed);
        assertEquals(0, AgcFlushCoalescer.get().pendingChannelCount());

        final var m = AgcFlushCoalescer.get().metrics();
        assertEquals(2, m.packetsCoalesced());
        assertEquals(2, m.flushesExecuted());
    }

    @Test
    void immediateFlushBypassesPending() {
        final EmbeddedChannel ch = new EmbeddedChannel();
        AgcFlushCoalescer.get().registerPendingFlush(ch);
        assertEquals(1, AgcFlushCoalescer.get().pendingChannelCount());

        AgcFlushCoalescer.get().recordImmediateFlush(ch);
        assertEquals(0, AgcFlushCoalescer.get().pendingChannelCount());
        assertEquals(1, AgcFlushCoalescer.get().metrics().immediateFlushes());
    }

    @Test
    void disabledCoalescerDoesNotRegister() {
        AgcFlushCoalescer.get().setEnabled(false);
        final EmbeddedChannel ch = new EmbeddedChannel();
        AgcFlushCoalescer.get().registerPendingFlush(ch);
        assertEquals(0, AgcFlushCoalescer.get().pendingChannelCount());
    }

    @Test
    void ticketRegistersOncePerTickButCountsEveryPacket() {
        final EmbeddedChannel ch = new EmbeddedChannel();
        final java.util.concurrent.atomic.AtomicBoolean ticket =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        for (int i = 0; i < 500; i++) {
            AgcFlushCoalescer.get().registerPendingFlush(ch, ticket);
        }

        assertEquals(1, AgcFlushCoalescer.get().pendingChannelCount(),
            "only the first packet of the tick may reach the channel map");
        assertEquals(500, AgcFlushCoalescer.get().metrics().packetsCoalesced());
        assertTrue(ticket.get(), "ticket must be armed while the channel is registered");

        assertEquals(1, AgcFlushCoalescer.get().flushPendingChannels());
        assertFalse(ticket.get(), "flush must release the ticket for the next tick");
        assertEquals(0, AgcFlushCoalescer.get().pendingChannelCount());

        // Next tick: the released ticket must allow a fresh registration.
        AgcFlushCoalescer.get().registerPendingFlush(ch, ticket);
        assertEquals(1, AgcFlushCoalescer.get().pendingChannelCount());
    }

    @Test
    void clearingPendingFlushesReleasesTickets() {
        final EmbeddedChannel ch = new EmbeddedChannel();
        final java.util.concurrent.atomic.AtomicBoolean ticket =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        AgcFlushCoalescer.get().registerPendingFlush(ch, ticket);
        assertTrue(ticket.get());

        AgcFlushCoalescer.get().resetMetrics();

        assertFalse(ticket.get(), "resetMetrics must not leave a stuck ticket behind");
        AgcFlushCoalescer.get().registerPendingFlush(ch, ticket);
        assertEquals(1, AgcFlushCoalescer.get().pendingChannelCount());
    }

    @Test
    void disablingCoalescerReleasesTickets() {
        final EmbeddedChannel ch = new EmbeddedChannel();
        final java.util.concurrent.atomic.AtomicBoolean ticket =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        AgcFlushCoalescer.get().registerPendingFlush(ch, ticket);
        assertTrue(ticket.get());

        AgcFlushCoalescer.get().setEnabled(false);

        assertFalse(ticket.get());
        assertEquals(0, AgcFlushCoalescer.get().pendingChannelCount());
    }

    @Test
    void immediateFlushReleasesTicket() {
        final EmbeddedChannel ch = new EmbeddedChannel();
        final java.util.concurrent.atomic.AtomicBoolean ticket =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        AgcFlushCoalescer.get().registerPendingFlush(ch, ticket);
        assertTrue(ticket.get());

        AgcFlushCoalescer.get().recordImmediateFlush(ch);

        assertFalse(ticket.get());
        assertEquals(0, AgcFlushCoalescer.get().pendingChannelCount());
    }

    @Test
    void manyChannelsWithIndependentTicketsRegisterIndependently() {
        final java.util.List<EmbeddedChannel> channels = new java.util.ArrayList<>();
        final java.util.List<java.util.concurrent.atomic.AtomicBoolean> tickets = new java.util.ArrayList<>();
        for (int i = 0; i < 32; i++) {
            channels.add(new EmbeddedChannel());
            tickets.add(new java.util.concurrent.atomic.AtomicBoolean(false));
        }

        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < channels.size(); i++) {
                AgcFlushCoalescer.get().registerPendingFlush(channels.get(i), tickets.get(i));
            }
            assertEquals(32, AgcFlushCoalescer.get().pendingChannelCount());
            assertEquals(32, AgcFlushCoalescer.get().flushPendingChannels());
            assertTrue(tickets.stream().noneMatch(java.util.concurrent.atomic.AtomicBoolean::get));
        }
        assertEquals(96, AgcFlushCoalescer.get().metrics().packetsCoalesced());
    }
}
