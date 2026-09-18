package io.papermc.paper.agc.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcAsyncEventPipelineTest {

    @BeforeEach
    public void setup() {
        AgcAsyncEventPipeline.get().clear();
    }

    @Test
    public void testAsyncEventDispatch() throws InterruptedException {
        AgcAsyncEventPipeline pipeline = AgcAsyncEventPipeline.get();
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger executed = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            pipeline.dispatchAsync("Test " + i, msg -> {
                executed.incrementAndGet();
                latch.countDown();
            });
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(5, executed.get());
        assertEquals(5, pipeline.metrics().asyncEventsSubmitted());
    }

    @Test
    public void testMovementEventCoalescing() {
        AgcAsyncEventPipeline pipeline = AgcAsyncEventPipeline.get();

        // Simulate 10 micro-movements for entity 42
        for (int i = 0; i < 10; i++) {
            pipeline.coalesceMoveEvent(42, "Move_Step_" + i);
        }

        // Entity 43 micro-movements
        for (int i = 0; i < 5; i++) {
            pipeline.coalesceMoveEvent(43, "Move_Step_" + i);
        }

        assertEquals(2, pipeline.metrics().pendingCoalescedEvents());
        assertEquals(13, pipeline.metrics().eventsCoalesced());

        // Flush
        List<Object> flushed = new ArrayList<>();
        pipeline.flushCoalescedMoveEvents(flushed::add);

        assertEquals(2, flushed.size());
        assertTrue(flushed.contains("Move_Step_9"));
        assertTrue(flushed.contains("Move_Step_4"));
    }
}
