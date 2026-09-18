package io.papermc.paper.agc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Performance Off-Heap Direct Buffer Pool.
 *
 * <p>Allocating large byte arrays on the JVM heap for chunk compression (zstd), network packet
 * serialization, and light updates forces frequent garbage collections. This pool manages
 * off-heap direct {@link ByteBuf} allocations to keep heap allocation rate near zero.</p>
 */
public final class AgcDirectBufferPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcDirectBufferPool.class);
    private static final AgcDirectBufferPool INSTANCE = new AgcDirectBufferPool();

    private final PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

    private final AtomicLong buffersAcquired = new AtomicLong();
    private final AtomicLong buffersReleased = new AtomicLong();
    private final AtomicLong directBytesAllocated = new AtomicLong();

    public static AgcDirectBufferPool get() {
        return INSTANCE;
    }

    private AgcDirectBufferPool() {}

    /**
     * Acquires a pooled direct off-heap buffer of at least the requested initial capacity.
     *
     * @param initialCapacity Initial capacity in bytes
     * @return Pooled direct ByteBuf (caller MUST release after use)
     */
    public ByteBuf acquireDirect(final int initialCapacity) {
        final int capacity = Math.max(256, initialCapacity);
        this.buffersAcquired.incrementAndGet();
        this.directBytesAllocated.addAndGet(capacity);
        return this.allocator.directBuffer(capacity);
    }

    /**
     * Releases an acquired ByteBuf back into the pooled allocator.
     *
     * @param buffer ByteBuf to release
     */
    public void releaseDirect(final ByteBuf buffer) {
        if (buffer == null) {
            return;
        }
        this.buffersReleased.incrementAndGet();
        if (buffer.refCnt() > 0) {
            try {
                buffer.release();
            } catch (final Throwable t) {
                LOGGER.warn("Failed to release direct ByteBuf", t);
            }
        }
    }

    public void resetMetrics() {
        this.buffersAcquired.set(0);
        this.buffersReleased.set(0);
        this.directBytesAllocated.set(0);
    }

    public BufferMetrics metrics() {
        return new BufferMetrics(
            this.buffersAcquired.get(),
            this.buffersReleased.get(),
            this.directBytesAllocated.get()
        );
    }

    public record BufferMetrics(
        long acquired,
        long released,
        long totalAllocatedBytes
    ) {
        public long activeBuffers() {
            return Math.max(0, this.acquired - this.released);
        }
    }
}
