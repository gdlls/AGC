package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcDirectIoChunkStorageEngineTest {

    @BeforeEach
    void setUp() {
        AgcDirectIoChunkStorageEngine.get().clearMetrics();
    }

    @Test
    void testChunkPayloadCompression() {
        final byte[] rawData = new byte[8192];
        for (int i = 0; i < rawData.length; i++) {
            rawData[i] = (byte) (i % 8); // Repetitive pattern
        }

        final byte[] compressed = AgcDirectIoChunkStorageEngine.get().compressChunkPayload(rawData);

        assertNotNull(compressed);
        assertTrue(compressed.length < rawData.length, "Compressed payload should be smaller than raw payload");

        final AgcDirectIoChunkStorageEngine.DirectIoMetrics metrics =
            AgcDirectIoChunkStorageEngine.get().metrics();

        assertEquals(1, metrics.totalChunksStored());
        assertEquals(rawData.length, metrics.totalRawBytes());
        assertEquals(compressed.length, metrics.totalCompressedBytes());
        assertTrue(metrics.compressionRatioPercent() > 50.0);
    }
}
