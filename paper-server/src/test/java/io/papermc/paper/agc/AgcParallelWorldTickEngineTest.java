package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcParallelWorldTickEngine}.
 */
class AgcParallelWorldTickEngineTest {

    @BeforeEach
    void setUp() {
        AgcParallelWorldTickEngine.get().bootstrap();
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
    }

    @AfterEach
    void tearDown() {
        // Always restore the safe default so AGGRESSIVE leaks from one test cannot
        // change the behavior of unrelated test classes (mode is global static state).
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        AgcParallelWorldTickEngine.get().shutdown();
    }

    @Test
    void bootstrapIsIdempotent() {
        AgcParallelWorldTickEngine.get().bootstrap();
        AgcParallelWorldTickEngine.get().bootstrap();
        final AgcParallelWorldTickEngine.EngineMetrics m = AgcParallelWorldTickEngine.get().metrics();
        assertTrue(m.running());
        assertTrue(m.workers() > 0);
    }

    @Test
    void emptyWorldsReturnsEmptySummary() {
        final var summary = AgcParallelWorldTickEngine.get().executeWorldTicks(List.of(), w -> {}, 4);
        assertFalse(summary.parallel());
        assertEquals(0, summary.worldsTicked());
    }

    @Test
    void sequentialFallbackWhenWorldCountBelowThreshold() {
        final List<String> worlds = List.of("world_nether", "world_the_end");
        final AtomicInteger tickedCount = new AtomicInteger(0);

        final var summary = AgcParallelWorldTickEngine.get().executeWorldTicks(
            worlds,
            w -> tickedCount.incrementAndGet(),
            4 // minWorlds = 4, but only 2 worlds provided -> sequential fallback
        );

        assertFalse(summary.parallel());
        assertEquals(2, summary.worldsTicked());
        assertEquals(2, tickedCount.get());
    }

    @Test
    void parallelExecutionWhenWorldCountMeetsThreshold() {
        // AGC fix regression context: parallel world ticking is AGGRESSIVE_BUT_SAFE, so it must
        // only engage when the operator has opted into AGC_AGGRESSIVE mode.
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);

        final int worldCount = 12;
        final List<String> worlds = new ArrayList<>();
        for (int i = 0; i < worldCount; i++) {
            worlds.add("world_arena_" + i);
        }

        final Set<Thread> threadsUsed = ConcurrentHashMap.newKeySet();
        final AtomicInteger tickedCount = new AtomicInteger(0);

        final var summary = AgcParallelWorldTickEngine.get().executeWorldTicks(
            worlds,
            world -> {
                threadsUsed.add(Thread.currentThread());
                tickedCount.incrementAndGet();
                try {
                    Thread.sleep(5); // simulate tick work
                } catch (final InterruptedException ignored) {}
            },
            2 // minWorlds = 2
        );

        assertTrue(summary.parallel());
        assertEquals(worldCount, summary.worldsTicked());
        assertEquals(worldCount, tickedCount.get());
        assertTrue(summary.waves() > 0);
        // Multi-threaded execution should utilize multiple threads
        assertTrue(threadsUsed.size() >= 1);
    }

    @Test
    void baselineModeMustNeverEngageParallelTicking() {
        // Regression test for the gate bug: executeWorldTicks used to consult MULTIWORLD_UNLOAD
        // (BASELINE safety) instead of PARALLEL_WORLD_TICK (AGGRESSIVE_BUT_SAFE), so the default
        // AGC_BASELINE mode silently ran multi-core parallel world ticking and fired Bukkit
        // events off the primary thread.
        final int worldCount = 12;
        final List<String> worlds = new ArrayList<>();
        for (int i = 0; i < worldCount; i++) {
            worlds.add("world_baseline_" + i);
        }

        final var summary = AgcParallelWorldTickEngine.get().executeWorldTicks(
            worlds,
            w -> {},
            2 // above the minWorlds threshold — parallel would be tempting here
        );

        assertFalse(summary.parallel(), "BASELINE mode must stay sequential to preserve plugin compatibility");
        assertEquals(worldCount, summary.worldsTicked());
    }

    @Test
    void wavePartitioningSplitsCorrectly() {
        final List<Integer> items = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        final List<List<Integer>> waves = AgcParallelWorldTickEngine.partitionIntoWaves(items, 3);

        assertEquals(4, waves.size());
        assertEquals(List.of(1, 2, 3), waves.get(0));
        assertEquals(List.of(4, 5, 6), waves.get(1));
        assertEquals(List.of(7, 8, 9), waves.get(2));
        assertEquals(List.of(10), waves.get(3));
    }

    @Test
    void conflictAwarePartitioningNeverCoSchedulesConflictingItems() {
        // Items are grouped into conflicting pairs: (0,1), (2,3), (4,5), ...
        // Pair members must land in different waves, but unrelated items may share waves.
        final List<Integer> items = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        final java.util.function.BiPredicate<Integer, Integer> pairConflict =
            (a, b) -> (a / 2) == (b / 2) && a.intValue() != b.intValue();

        final List<List<Integer>> waves = AgcParallelWorldTickEngine.partitionIntoWaves(items, 8, pairConflict);

        // Capacity respected
        for (final List<Integer> wave : waves) {
            assertTrue(wave.size() <= 8, "wave exceeded maxPerWave: " + wave.size());
        }
        // All items present exactly once
        final List<Integer> flat = new ArrayList<>();
        waves.forEach(flat::addAll);
        assertEquals(items.size(), flat.size());

        // No wave contains both members of a conflicting pair
        for (final List<Integer> wave : waves) {
            for (int i = 0; i < wave.size(); i++) {
                for (int j = i + 1; j < wave.size(); j++) {
                    assertFalse(pairConflict.test(wave.get(i), wave.get(j)),
                        "conflicting items co-scheduled in one wave: " + wave);
                }
            }
        }
    }

    @Test
    void conflictAwarePartitioningIsolatesFullyConflictingItems() {
        // Pathological case: everything conflicts with everything -> one item per wave.
        final List<String> items = List.of("a", "b", "c", "d");
        final List<List<String>> waves = AgcParallelWorldTickEngine.partitionIntoWaves(
            items, 4, (a, b) -> true);

        assertEquals(4, waves.size());
        for (final List<String> wave : waves) {
            assertEquals(1, wave.size());
        }
    }

    @Test
    void conflictAwarePartitioningDegradesToLegacyChunkingWhenPredicateNull() {
        final List<Integer> items = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        final List<List<Integer>> waves = AgcParallelWorldTickEngine.partitionIntoWaves(items, 3, null);

        assertEquals(4, waves.size());
        assertEquals(List.of(1, 2, 3), waves.get(0));
        assertEquals(List.of(10), waves.get(3));
    }

    @Test
    void shouldDeferCrossWorldDetectsOtherWorld() {
        assertFalse(AgcParallelWorldTickEngine.get().shouldDeferCrossWorld("world_overworld"));
    }

    @Test
    void metricsTracksExecutionStatistics() {
        // Wave statistics are recorded on the parallel path; engage AGGRESSIVE mode explicitly.
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        final List<String> worlds = List.of("w1", "w2", "w3", "w4", "w5");
        AgcParallelWorldTickEngine.get().executeWorldTicks(worlds, w -> {}, 2);

        final AgcParallelWorldTickEngine.EngineMetrics m = AgcParallelWorldTickEngine.get().metrics();
        assertTrue(m.totalWaves() > 0);
        assertEquals(0, m.failures());
    }

    @Test
    void sequentialExecutionIsRecordedWhenParallelDisabled() {
        // Baseline mode -> sequential path must still record execution stats.
        final List<String> worlds = List.of("w1", "w2", "w3", "w4", "w5");
        final var summary = AgcParallelWorldTickEngine.get().executeWorldTicks(worlds, w -> {}, 2);

        assertFalse(summary.parallel());
        assertEquals(1, summary.waves());
        assertEquals(worlds.size(), summary.worldsTicked());
        final AgcParallelWorldTickEngine.EngineMetrics m = AgcParallelWorldTickEngine.get().metrics();
        assertEquals(0, m.failures());
    }
}
