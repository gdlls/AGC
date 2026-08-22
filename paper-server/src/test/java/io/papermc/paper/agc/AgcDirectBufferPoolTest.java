package io.papermc.paper.agc;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcDirectBufferPool}.
 */
class AgcDirectBufferPoolTest {

    @BeforeEach
    @AfterEach
    void resetPool() {
        AgcDirectBufferPool.get().resetMetrics();
    }

    @Test
    void acquireAndReleaseDirectBuffer() {
        final ByteBuf buf = AgcDirectBufferPool.get().acquireDirect(1024);
        assertNotNull(buf);
        assertTrue(buf.isDirect());
        assertTrue(buf.capacity() >= 1024);

        buf.writeInt(42);
        assertEquals(42, buf.readInt());

        AgcDirectBufferPool.get().releaseDirect(buf);

        final var m = AgcDirectBufferPool.get().metrics();
        assertEquals(1, m.acquired());
        assertEquals(1, m.released());
        assertEquals(0, m.activeBuffers());
        assertTrue(m.totalAllocatedBytes() >= 1024);
    }
}
