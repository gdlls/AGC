package io.papermc.paper.agc.tick;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AgcBlockTickBatcherTest {

    @BeforeEach
    public void setup() {
        AgcBlockTickBatcher.get().clear();
    }

    @Test
    public void testScheduledBlockTickBatchingAndExecution() {
        AgcBlockTickBatcher batcher = AgcBlockTickBatcher.get();

        // Queue ticks for tick 100 in chunk (0, 0) and (1, 1)
        batcher.scheduleTick(100, 0, 0, 5, 64, 5, "minecraft:water");
        batcher.scheduleTick(100, 0, 0, 6, 64, 5, "minecraft:water");
        batcher.scheduleTick(100, 1, 1, 20, 64, 20, "minecraft:lava");

        // Queue tick for tick 101
        batcher.scheduleTick(101, 0, 0, 7, 64, 5, "minecraft:water");

        assertEquals(4, batcher.metrics().scheduledTicksEnqueued());

        // Process tick 100
        List<AgcBlockTickBatcher.ScheduledBlockTick> executed = new ArrayList<>();
        int count100 = batcher.processTicksForCurrentTick(100, executed::add);

        assertEquals(3, count100);
        assertEquals(3, executed.size());
        assertEquals(2, batcher.metrics().chunkBatchesProcessed());

        // Process tick 100 again -> should be empty (already consumed)
        int count100Again = batcher.processTicksForCurrentTick(100, executed::add);
        assertEquals(0, count100Again);

        // Process tick 101
        int count101 = batcher.processTicksForCurrentTick(101, executed::add);
        assertEquals(1, count101);
        assertEquals(4, executed.size());
    }
}
