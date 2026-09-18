package io.papermc.paper.agc.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcAsyncSavePipelineTest {

    @BeforeEach
    public void setup() {
        AgcAsyncSavePipeline.get().clear();
    }

    @Test
    public void testAsyncChunkSaveAndCoalescing() throws InterruptedException {
        AgcAsyncSavePipeline pipeline = AgcAsyncSavePipeline.get();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean(false);

        // Queue 1st task that waits on startLatch so it stays in pendingSaves
        boolean queued1 = pipeline.queueChunkSave("world", 100L, new byte[]{1, 2, 3}, () -> {
            try {
                startLatch.await(10, TimeUnit.SECONDS);
                saved.set(true);
            } catch (InterruptedException ignored) {}
            doneLatch.countDown();
        });
        assertTrue(queued1);

        // 2nd duplicate submission while 1st is still pending -> coalesced!
        boolean queued2 = pipeline.queueChunkSave("world", 100L, new byte[]{4, 5, 6}, () -> {
            saved.set(true);
            doneLatch.countDown();
        });
        assertTrue(queued2);

        assertEquals(1, pipeline.metrics().savesCoalesced());
        assertEquals(1, pipeline.metrics().chunksQueued());

        // Release latch and wait for worker completion
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        assertTrue(saved.get());

        // Wait up to 5 seconds for async pipeline worker to increment completion counter
        final long deadline = System.currentTimeMillis() + 5000L;
        while (pipeline.metrics().chunksSavedAsync() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }

        assertEquals(1, pipeline.metrics().chunksSavedAsync());
    }
}
