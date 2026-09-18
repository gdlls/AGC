package io.papermc.paper.agc.tick;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcRegionTickBridgeTest {

    @Test
    public void testClusterKeyGroupsCells() {
        // 8-chunk cells: chunks 0..7 share a region, 8 starts the next.
        assertEquals(AgcRegionTickBridge.clusterKey(0, 0), AgcRegionTickBridge.clusterKey(7, 7));
        assertFalse(AgcRegionTickBridge.clusterKey(0, 0) == AgcRegionTickBridge.clusterKey(8, 0));
        assertFalse(AgcRegionTickBridge.clusterKey(0, 0) == AgcRegionTickBridge.clusterKey(0, 8));
        // Negative chunks group deterministically (arithmetic shift).
        assertEquals(AgcRegionTickBridge.clusterKey(-1, -1), AgcRegionTickBridge.clusterKey(-8, -8));
        assertFalse(AgcRegionTickBridge.clusterKey(-1, 0) == AgcRegionTickBridge.clusterKey(0, 0));
    }

    @Test
    public void testReadOnlyInlineFallbackWithoutBootstrap() throws Exception {
        // No bootstrap in unit tests -> inline execution, identical result.
        final Future<String> future = AgcRegionTickBridge.get().submitReadOnly(() -> "parity");
        assertTrue(future.isDone());
        assertEquals("parity", future.get());
    }

    @Test
    public void testCommitsDrainFifoOnCaller() {
        final var bridge = AgcRegionTickBridge.get();
        final List<Integer> order = new ArrayList<>();
        bridge.commitOnPrimary(() -> order.add(1));
        bridge.commitOnPrimary(() -> order.add(2));
        bridge.commitOnPrimary(() -> {
            order.add(3);
            throw new RuntimeException("commit failure must not stop the drain");
        });
        bridge.commitOnPrimary(() -> order.add(4));
        final int drained = bridge.drainCommits();
        assertEquals(3, drained);
        assertEquals(List.of(1, 2, 3, 4), order);
        assertEquals(0, bridge.pendingCommits());
    }

    @Test
    public void testAwaitHelpersEmpty() {
        assertTrue(AgcRegionTickBridge.get().awaitHelpers(10));
    }

    @Test
    public void testBootstrapHelperOffloadAndCommit() throws Exception {
        final var bridge = AgcRegionTickBridge.get();
        bridge.bootstrap();
        try {
            final AtomicInteger helperRuns = new AtomicInteger();
            // In BASELINE test mode the aggressive bridge runs inline — result identical.
            final Future<Integer> future = bridge.submitReadOnly(() -> {
                helperRuns.incrementAndGet();
                return 40 + 2;
            });
            assertEquals(42, future.get().intValue());
            assertEquals(1, helperRuns.get());

            final AtomicInteger commits = new AtomicInteger();
            bridge.commitOnPrimary(commits::incrementAndGet);
            assertEquals(1, bridge.drainCommits());
            assertEquals(1, commits.get());
            assertTrue(bridge.awaitHelpers(1000));
        } finally {
            bridge.shutdown();
        }
    }
}
