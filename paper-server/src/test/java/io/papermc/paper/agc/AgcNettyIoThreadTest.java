package io.papermc.paper.agc;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the network-thread contract that the AGC network fast paths depend on.
 *
 * <p>Netty keeps per-connection/per-thread state (the {@code ChannelOutboundBuffer} entry
 * recycler, {@code CodecOutputList}, the gathered-write {@code ByteBuffer[]}, the buffer
 * allocator magazines) in {@link FastThreadLocal}s. {@code InternalThreadLocalMap.get()} only
 * takes the fast path when the current thread is a {@link FastThreadLocalThread}; every other
 * thread type falls back to {@code slowGet()}, which probes a {@code ThreadLocal} hash table on
 * each and every access. A live JFR profile of a 300-bot run attributed 3.8% of all JVM
 * execution samples to that fallback, ~95% of them on the four IO threads.</p>
 *
 * <p>{@code EventLoopGroupHolder} builds its threads with Guava's {@code ThreadFactoryBuilder},
 * which hands out plain {@code Thread}s. These tests fail if that regresses, including if a
 * future Netty or Guava change silently drops the custom backing thread factory.</p>
 */
class AgcNettyIoThreadTest {

    /** The holder caches one shared group per transport type, so the tests must share it too. */
    private static final EventLoopGroup GROUP = EventLoopGroupHolder.remote(false).eventLoopGroup();

    @AfterAll
    static void shutdown() throws InterruptedException {
        GROUP.shutdownGracefully().await(10, TimeUnit.SECONDS);
    }

    @Test
    void eventLoopThreadsAreFastThreadLocalThreads() throws Exception {
        final Thread thread = GROUP.submit(Thread::currentThread).get(10, TimeUnit.SECONDS);

        assertInstanceOf(FastThreadLocalThread.class, thread,
            "Netty IO threads must be FastThreadLocalThreads, otherwise every FastThreadLocal access "
                + "on the network hot path pays a ThreadLocal map probe");
        assertTrue(thread.getName().startsWith("Netty NIO IO #"),
            "IO thread naming must be preserved for diagnostics, got: " + thread.getName());
        assertTrue(thread.isDaemon(), "IO threads must stay daemon threads");
    }

    @Test
    void fastThreadLocalStateIsStoredOnTheThread() throws Exception {
        final FastThreadLocal<Object> local = new FastThreadLocal<>();
        final boolean fastMapUsed = GROUP.submit(() -> {
            // A plain get() is enough: the fast path materialises and stores the map on the thread,
            // while the slow path would leave threadLocalMap() null (the value would live in the
            // thread's ordinary ThreadLocal table instead).
            local.get();
            local.remove();
            return ((FastThreadLocalThread) Thread.currentThread()).threadLocalMap() != null;
        }).get(10, TimeUnit.SECONDS);

        assertTrue(fastMapUsed,
            "FastThreadLocal lookups on an IO thread must use the thread-local fast map");
    }
}
