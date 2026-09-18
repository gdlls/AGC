package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgcNumaWorkStealingSchedulerTest {

    @BeforeEach
    void setUp() {
        AgcNumaWorkStealingScheduler.get().clearMetrics();
        AgcNumaWorkStealingScheduler.get().bootstrap(8, 2); // 2 NUMA nodes x 4 workers = 8 threads
    }

    @AfterEach
    void tearDown() {
        AgcNumaWorkStealingScheduler.get().shutdown();
    }

    @Test
    void testNumaBatchExecution() {
        final int taskCount = 100;
        final AtomicInteger executed = new AtomicInteger();
        final List<Runnable> tasks = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            tasks.add(executed::incrementAndGet);
        }

        AgcNumaWorkStealingScheduler.get().executeBatchSync(tasks);

        assertEquals(taskCount, executed.get());
        final AgcNumaWorkStealingScheduler.SchedulerMetrics metrics = AgcNumaWorkStealingScheduler.get().metrics();
        assertEquals(taskCount, metrics.tasksSubmitted());
        assertTrue(metrics.tasksExecutedLocal() + metrics.tasksStolen() >= taskCount);
    }
}
