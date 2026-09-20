package io.papermc.paper.agc.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class AgcOffHeapSlabAllocatorTest {

    @BeforeEach
    @AfterEach
    public void reset() {
        AgcOffHeapSlabAllocator.get().clear();
    }

    @Test
    public void testSlabAcquisitionTiers() {
        final AgcOffHeapSlabAllocator allocator = AgcOffHeapSlabAllocator.get();

        // 512B -> Tier 1K
        final ByteBuffer buf1 = allocator.acquire(512);
        assertTrue(buf1.isDirect());
        assertEquals(AgcOffHeapSlabAllocator.SLAB_1K, buf1.capacity());

        // 2048B -> Tier 4K
        final ByteBuffer buf2 = allocator.acquire(2048);
        assertTrue(buf2.isDirect());
        assertEquals(AgcOffHeapSlabAllocator.SLAB_4K, buf2.capacity());

        // 10240B -> Tier 16K
        final ByteBuffer buf3 = allocator.acquire(10240);
        assertTrue(buf3.isDirect());
        assertEquals(AgcOffHeapSlabAllocator.SLAB_16K, buf3.capacity());

        // 32768B -> Tier 64K
        final ByteBuffer buf4 = allocator.acquire(32768);
        assertTrue(buf4.isDirect());
        assertEquals(AgcOffHeapSlabAllocator.SLAB_64K, buf4.capacity());

        final var m = allocator.metrics();
        assertEquals(1, m.slab1kAcquisitions());
        assertEquals(1, m.slab4kAcquisitions());
        assertEquals(1, m.slab16kAcquisitions());
        assertEquals(1, m.slab64kAcquisitions());

        allocator.release(buf1);
        allocator.release(buf2);
        allocator.release(buf3);
        allocator.release(buf4);

        assertEquals(4, allocator.metrics().totalReleases());
    }

    @Test
    public void testTrimToFitKeepsPrewarmBaseline() {
        final AgcOffHeapSlabAllocator allocator = AgcOffHeapSlabAllocator.get();

        // Flood the 1K pool well beyond the prewarm baseline (32).
        final java.util.List<ByteBuffer> held = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            held.add(allocator.acquire(512));
        }
        for (final ByteBuffer buf : held) {
            allocator.release(buf);
        }
        assertTrue(allocator.metrics().pooled1k() > 32);

        final int dropped = allocator.trimToFit();
        assertTrue(dropped > 0);
        assertEquals(32, allocator.metrics().pooled1k());
    }

    @Test
    public void testRecycledBufferIsCleared() {
        final AgcOffHeapSlabAllocator allocator = AgcOffHeapSlabAllocator.get();

        final ByteBuffer buf = allocator.acquire(1024);
        buf.putInt(42);
        buf.putInt(999);
        assertEquals(8, buf.position());

        allocator.release(buf);

        // Reacquire: buffer should have position=0 and limit=capacity
        final ByteBuffer reacquired = allocator.acquire(1024);
        assertEquals(0, reacquired.position());
        assertEquals(AgcOffHeapSlabAllocator.SLAB_1K, reacquired.limit());

        allocator.release(reacquired);
    }
}
