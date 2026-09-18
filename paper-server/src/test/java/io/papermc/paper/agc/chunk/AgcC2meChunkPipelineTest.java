package io.papermc.paper.agc.chunk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcC2meChunkPipelineTest {

    @BeforeEach
    public void setup() {
        AgcC2meChunkPipeline.get().clearMetrics();
    }

    @Test
    public void testIoThreadAutosizing() {
        // Baseline scales with cores, grows with players, clamps sanely.
        assertEquals(2, AgcC2meChunkPipeline.computeIoThreads(8, 0));
        assertTrue(AgcC2meChunkPipeline.computeIoThreads(128, 5000) > AgcC2meChunkPipeline.computeIoThreads(128, 0));
        assertTrue(AgcC2meChunkPipeline.computeIoThreads(8, 100000) <= 8);
        assertTrue(AgcC2meChunkPipeline.computeIoThreads(2, 0) >= 2);
    }

    @Test
    public void testGenThreadAutosizing() {
        // Pre-generated worlds need far fewer gen threads.
        assertTrue(AgcC2meChunkPipeline.computeGenThreads(32, true) < AgcC2meChunkPipeline.computeGenThreads(32, false));
        assertTrue(AgcC2meChunkPipeline.computeGenThreads(8, false) >= 2);
    }

    @Test
    public void testBackpressurePermitFlow() {
        final var pipe = AgcC2meChunkPipeline.get();
        final int permits = pipe.availableGenPermits();
        assertTrue(permits > 0);
        assertTrue(pipe.tryAcquireGenPermit(permits));
        pipe.releaseGenPermit();
        assertEquals(permits, pipe.availableGenPermits());
    }

    @Test
    public void testTicketThrottlePreservesHead() {
        final var pipe = AgcC2meChunkPipeline.get();
        assertTrue(pipe.admitTicket(10, 128));
        assertTrue(pipe.admitTicket(128, 128));
        assertFalse(pipe.admitTicket(100000, 128));
        assertTrue(pipe.metrics().throttledTickets() >= 1);
        assertTrue(pipe.metrics().admittedTickets() >= 2);
    }

    @Test
    public void testNoTickViewDistancePolicy() {
        // Margin 0 = vanilla behavior.
        assertEquals(12, AgcC2meChunkPipeline.effectiveTickRadius(12, 0));
        assertEquals(10, AgcC2meChunkPipeline.effectiveTickRadius(12, 2));
        // Never below vanilla minimum.
        assertEquals(2, AgcC2meChunkPipeline.effectiveTickRadius(4, 10));
        assertEquals(2, AgcC2meChunkPipeline.effectiveTickRadius(2, 0));
    }

    @Test
    public void testSerializeAsyncInlineFallback() throws Exception {
        // No bootstrap in unit tests -> inline, identical bytes.
        final var future = AgcC2meChunkPipeline.get().serializeAsync(() -> new byte[] {1, 2, 3});
        assertTrue(future.isDone());
        assertEquals(3, future.get().length);
    }
}
