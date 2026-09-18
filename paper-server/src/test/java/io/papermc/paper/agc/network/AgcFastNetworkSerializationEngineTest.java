package io.papermc.paper.agc.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcFastNetworkSerializationEngineTest {

    @BeforeEach
    @AfterEach
    public void reset() {
        AgcFastNetworkSerializationEngine.get().clear();
    }

    @Test
    public void testVarIntSizeComputation() {
        assertEquals(1, AgcFastNetworkSerializationEngine.computeVarIntSize(0));
        assertEquals(1, AgcFastNetworkSerializationEngine.computeVarIntSize(127));
        assertEquals(2, AgcFastNetworkSerializationEngine.computeVarIntSize(128));
        assertEquals(2, AgcFastNetworkSerializationEngine.computeVarIntSize(16383));
        assertEquals(3, AgcFastNetworkSerializationEngine.computeVarIntSize(16384));
        assertEquals(3, AgcFastNetworkSerializationEngine.computeVarIntSize(2097151));
        assertEquals(4, AgcFastNetworkSerializationEngine.computeVarIntSize(2097152));
        assertEquals(5, AgcFastNetworkSerializationEngine.computeVarIntSize(-1));
    }

    @Test
    public void testVarIntRoundTripCodec() {
        final AgcFastNetworkSerializationEngine engine = AgcFastNetworkSerializationEngine.get();
        final byte[] buffer = new byte[32];
        final int[] bytesRead = new int[1];

        final int[] testValues = new int[]{0, 1, 127, 128, 255, 256, 32767, 65535, 1000000, -1, Integer.MAX_VALUE, Integer.MIN_VALUE};

        for (final int original : testValues) {
            final int written = engine.writeVarInt(buffer, 0, original);
            assertEquals(AgcFastNetworkSerializationEngine.computeVarIntSize(original), written);

            final int decoded = engine.readVarInt(buffer, 0, bytesRead);
            assertEquals(original, decoded);
            assertEquals(written, bytesRead[0]);
        }

        final var metrics = engine.metrics();
        assertEquals(testValues.length, metrics.varIntsEncoded());
        assertEquals(testValues.length, metrics.varIntsDecoded());
    }

    @Test
    public void testCompressionBypassThreshold() {
        final AgcFastNetworkSerializationEngine engine = AgcFastNetworkSerializationEngine.get();

        // Under 256 bytes -> bypass
        assertTrue(engine.shouldBypassCompression(100));
        assertTrue(engine.shouldBypassCompression(255));

        // At or above 256 bytes -> do not bypass
        assertFalse(engine.shouldBypassCompression(256));
        assertFalse(engine.shouldBypassCompression(1024));

        assertEquals(2, engine.metrics().compressionBypassed());
    }

    @Test
    public void testBufferRecyclingPool() {
        final AgcFastNetworkSerializationEngine engine = AgcFastNetworkSerializationEngine.get();

        final byte[] buf = engine.acquireBuffer();
        assertNotNull(buf);
        assertEquals(AgcFastNetworkSerializationEngine.DEFAULT_BUFFER_SIZE, buf.length);

        engine.releaseBuffer(buf);

        final var m = engine.metrics();
        assertTrue(m.buffersAcquired() >= 1);
        assertTrue(m.buffersRecycled() >= 1);
    }

    @Test
    public void testVarLongSizeComputation() {
        assertEquals(1, AgcFastNetworkSerializationEngine.computeVarLongSize(0L));
        assertEquals(1, AgcFastNetworkSerializationEngine.computeVarLongSize(127L));
        assertEquals(2, AgcFastNetworkSerializationEngine.computeVarLongSize(128L));
        assertEquals(2, AgcFastNetworkSerializationEngine.computeVarLongSize(16383L));
        assertEquals(3, AgcFastNetworkSerializationEngine.computeVarLongSize(16384L));
        assertEquals(9, AgcFastNetworkSerializationEngine.computeVarLongSize(Long.MAX_VALUE));
        assertEquals(10, AgcFastNetworkSerializationEngine.computeVarLongSize(-1L));
        assertEquals(10, AgcFastNetworkSerializationEngine.computeVarLongSize(Long.MIN_VALUE));
    }

    @Test
    public void testVarLongRoundTripCodec() {
        final AgcFastNetworkSerializationEngine engine = AgcFastNetworkSerializationEngine.get();
        final byte[] buffer = new byte[32];
        final int[] bytesRead = new int[1];

        final long[] testValues = new long[]{
            0L, 1L, 127L, 128L, 255L, 256L, 32767L, 65535L, 1000000L,
            -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x123456789ABCDEFL, -9876543210L
        };

        for (final long original : testValues) {
            final int written = engine.writeVarLong(buffer, 0, original);
            assertEquals(AgcFastNetworkSerializationEngine.computeVarLongSize(original), written);

            final long decoded = engine.readVarLong(buffer, 0, bytesRead);
            assertEquals(original, decoded);
            assertEquals(written, bytesRead[0]);
        }

        final var metrics = engine.metrics();
        assertEquals(testValues.length, metrics.varLongsEncoded());
        assertEquals(testValues.length, metrics.varLongsDecoded());
    }
}
