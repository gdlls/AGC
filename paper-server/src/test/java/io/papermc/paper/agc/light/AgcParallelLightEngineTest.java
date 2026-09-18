package io.papermc.paper.agc.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcParallelLightEngineTest {

    @Test
    public void testPlanRangesCoverExactly() {
        final var engine = AgcParallelLightEngine.get();
        final List<int[]> ranges = engine.planRanges(100, 4);
        assertEquals(4, ranges.size());
        int total = 0;
        int cursor = 0;
        for (final int[] range : ranges) {
            assertEquals(cursor, range[0]);
            assertTrue(range[1] > range[0]);
            total += range[1] - range[0];
            cursor = range[1];
        }
        assertEquals(100, total);
        assertEquals(100, cursor);
    }

    @Test
    public void testPlanRangesClampToSections() {
        final var engine = AgcParallelLightEngine.get();
        final List<int[]> ranges = engine.planRanges(3, 16);
        assertEquals(3, ranges.size());
        assertTrue(engine.planRanges(0, 4).isEmpty());
    }

    @Test
    public void testDispatchInlineFallbackWithoutBootstrap() throws Exception {
        // No bootstrap in unit tests -> pool is null -> inline execution, identical results.
        final var engine = AgcParallelLightEngine.get();
        final AtomicInteger sum = new AtomicInteger();
        final int processed = engine.dispatchSections(64, sum::addAndGet);
        assertEquals(64, processed);
        // 0+1+...+63 = 2016
        assertEquals(2016, sum.get());
    }

    @Test
    public void testDispatchEmpty() throws Exception {
        final var engine = AgcParallelLightEngine.get();
        assertEquals(0, engine.dispatchSections(0, i -> {
            throw new AssertionError("must not run");
        }));
        assertEquals(0, engine.dispatchSections(10, null));
    }

    @Test
    public void testBootstrapDispatchShutdownCycle() throws Exception {
        final var engine = AgcParallelLightEngine.get();
        engine.bootstrap();
        try {
            final List<Integer> seen = java.util.Collections.synchronizedList(new ArrayList<>());
            // Capability gate is BASELINE-mode OFF for the aggressive feature, so this
            // still runs inline — but must produce complete results either way.
            final int processed = engine.dispatchSections(32, seen::add);
            assertEquals(32, processed);
            assertEquals(32, seen.size());
        } finally {
            engine.shutdown();
        }
    }
}
