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
}
