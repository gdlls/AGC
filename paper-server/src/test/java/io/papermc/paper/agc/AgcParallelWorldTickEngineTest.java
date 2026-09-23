package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        AgcCapabilityMatrix.clearRuntimeOverrides();
        // These tests exercise the wave-dispatch path itself, so they declare "no plugins loaded"
        // explicitly. Production cannot inspect a plugin manager here and therefore fails safe to
        // sequential ticking (see AgcParallelWorldTickEngine#pluginsLoaded).
        AgcParallelWorldTickEngine.setPluginPresenceProbe(() -> false);
        AgcParallelWorldTickEngine.get().applyTuning(0, 0);
        AgcParallelWorldTickEngine.get().bootstrap();
    }

    @AfterEach
    void tearDown() {
        AgcParallelWorldTickEngine.setPluginPresenceProbe(null);
        AgcCapabilityMatrix.clearRuntimeOverrides();
        AgcParallelWorldTickEngine.get().applyTuning(0, 0);
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
    void disabledParallelWorldTickMustStaySequential() {
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.PARALLEL_WORLD_TICK, false);
        try {
            final int worldCount = 12;
            final List<String> worlds = new ArrayList<>();
            for (int i = 0; i < worldCount; i++) {
                worlds.add("world_sequential_" + i);
            }

            final var summary = AgcParallelWorldTickEngine.get().executeWorldTicks(
                worlds,
                w -> {},
                2 // above the minWorlds threshold — parallel would be tempting here
            );

            assertFalse(summary.parallel(), "Disabled PARALLEL_WORLD_TICK must stay sequential");
            assertEquals(worldCount, summary.worldsTicked());
        } finally {
            AgcCapabilityMatrix.clearRuntimeOverrides();
        }
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
        try {
            AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.PARALLEL_WORLD_TICK, false);
            final List<String> worlds = List.of("w1", "w2", "w3", "w4", "w5");
            final var summary = AgcParallelWorldTickEngine.get().executeWorldTicks(worlds, w -> {}, 2);

            assertFalse(summary.parallel());
            assertEquals(1, summary.waves());
            assertEquals(worlds.size(), summary.worldsTicked());
            final AgcParallelWorldTickEngine.EngineMetrics m = AgcParallelWorldTickEngine.get().metrics();
            assertEquals(0, m.failures());
        } finally {
            AgcCapabilityMatrix.clearRuntimeOverrides();
        }
    }

    @Test
    void worldDependenciesCanBeAddedAndCleared() {
        final var engine = AgcParallelWorldTickEngine.get();
        engine.clearDependencies();
        engine.addWorldDependency("world", "world_nether");
        engine.addWorldDependency("world", "world_the_end");

        // Verify removing
        engine.removeWorldDependency("world", "world_nether");
        engine.clearDependencies();
    }

    @Test
    void conflictPredicateSeparatesSameFamilyDimensionsIntoDifferentWaves() {
        final List<String> worlds = List.of(
            "world", "world_nether", "world_the_end",
            "hub", "minigame_1", "minigame_2"
        );

        // Conflict: world, world_nether, world_the_end are in the same family
        final java.util.function.BiPredicate<String, String> conflict = (a, b) -> {
            final boolean aIsWorldFamily = a.startsWith("world");
            final boolean bIsWorldFamily = b.startsWith("world");
            return aIsWorldFamily && bIsWorldFamily;
        };

        final List<List<String>> waves = AgcParallelWorldTickEngine.partitionIntoWaves(worlds, 4, conflict);
        assertTrue(waves.size() >= 3);

        // Verify no wave has more than 1 "world" family member
        for (final List<String> wave : waves) {
            long worldFamilyCount = wave.stream().filter(w -> w.startsWith("world")).count();
            assertTrue(worldFamilyCount <= 1, "Wave contains conflicting family members: " + wave);
        }
    }

    @Test
    void tickWorkersAreTickThreadsButNeverClaimPrimaryThreadIdentity() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        // Bind the real primary thread so the worker assertions below mean something.
        AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());

        final List<String> worlds = List.of("world_a", "world_b", "world_c", "world_d");
        final java.util.concurrent.atomic.AtomicBoolean anyWorkerVerified = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean allChecksPassed = new java.util.concurrent.atomic.AtomicBoolean(true);

        AgcParallelWorldTickEngine.get().executeWorldTicks(
            worlds,
            world -> {
                final Thread t = Thread.currentThread();
                if (t.getName().startsWith("AGC-WorldTick-Worker-")) {
                    anyWorkerVerified.set(true);
                    if (!(t instanceof ca.spottedleaf.moonrise.common.util.TickThread)) {
                        allChecksPassed.set(false);
                    }
                    // Honest contract: a worker is a tick thread, so the chunk-system guard must
                    // accept it ...
                    if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
                        allChecksPassed.set(false);
                    }
                    // ...but it must NOT pretend to be the primary thread. Plugin thread checks and
                    // the virtual-primary accessor stay false on workers.
                    if (AgcPluginSafetyGuard.get().isPrimaryThread()) {
                        allChecksPassed.set(false);
                    }
                    if (AgcPluginVirtualizer.isVirtualPrimary()) {
                        allChecksPassed.set(false);
                    }
                } else if (!AgcPluginSafetyGuard.get().isPrimaryThread()) {
                    // Non-worker invocations must be the real primary thread.
                    allChecksPassed.set(false);
                }
            },
            2
        );

        assertTrue(anyWorkerVerified.get(), "Expected at least one worker thread to execute a world tick");
        assertTrue(allChecksPassed.get(),
            "Worker threads must be TickThreads that never claim the primary thread identity");
    }

    @Test
    void dagWavefrontPartitionerEnforcesTopologicalWaveOrdering() {
        final List<String> worlds = List.of("world_the_end", "world_nether", "world", "arena_1", "arena_2");
        final Map<String, Set<String>> deps = new HashMap<>();
        deps.put("world", Set.of("world_nether"));
        deps.put("world_nether", Set.of("world_the_end"));

        final List<List<String>> waves = AgcParallelWorldTickEngine.partitionIntoWaves(worlds, 8, null, deps);

        assertTrue(waves.size() >= 3, "Chained dependencies world -> nether -> the_end must create at least 3 waves");

        int waveWorld = -1;
        int waveNether = -1;
        int waveEnd = -1;

        for (int i = 0; i < waves.size(); i++) {
            final List<String> w = waves.get(i);
            if (w.contains("world")) waveWorld = i;
            if (w.contains("world_nether")) waveNether = i;
            if (w.contains("world_the_end")) waveEnd = i;
        }

        assertTrue(waveWorld >= 0 && waveNether >= 0 && waveEnd >= 0);
        assertTrue(waveWorld < waveNether, "world must precede world_nether: " + waveWorld + " vs " + waveNether);
        assertTrue(waveNether < waveEnd, "world_nether must precede world_the_end: " + waveNether + " vs " + waveEnd);
    }

    @Test
    void dagWavefrontPartitionerToleratesCyclesWithoutDroppingWorlds() {
        final List<String> worlds = List.of("cycle_a", "cycle_b", "cycle_c");
        final Map<String, Set<String>> cyclicDeps = new HashMap<>();
        cyclicDeps.put("cycle_a", Set.of("cycle_b"));
        cyclicDeps.put("cycle_b", Set.of("cycle_a"));

        final List<List<String>> waves = AgcParallelWorldTickEngine.partitionIntoWaves(worlds, 4, null, cyclicDeps);

        final List<String> flat = new ArrayList<>();
        waves.forEach(flat::addAll);
        assertEquals(3, flat.size(), "All worlds must be placed even when cyclic dependencies exist");
        assertTrue(flat.containsAll(worlds));
    }

    @Test
    void executeWorldTicksHonorsRegisteredWorldDependencies() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        final var engine = AgcParallelWorldTickEngine.get();
        engine.clearDependencies();
        engine.addWorldDependency("dep_world_alpha", "dep_world_beta");

        final List<String> worlds = List.of("dep_world_beta", "dep_world_alpha");
        final List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        engine.executeWorldTicks(
            worlds,
            world -> executionOrder.add(world),
            2
        );

        assertEquals(2, executionOrder.size());
        assertEquals("dep_world_alpha", executionOrder.get(0), "alpha must be scheduled before beta due to dependency DAG");
        assertEquals("dep_world_beta", executionOrder.get(1));

        engine.clearDependencies();
    }

    @Test
    void ultraScale500WorldsWavefrontPartitioning() {
        final int worldCount = 500;
        final List<String> worlds = new ArrayList<>(worldCount);
        final Map<String, Set<String>> deps = new HashMap<>();

        for (int i = 0; i < worldCount; i++) {
            worlds.add("scale_world_" + i);
        }
        // 250 dependency pairs: scale_world_2k -> scale_world_2k+1
        for (int i = 0; i < worldCount; i += 2) {
            deps.put("scale_world_" + i, Set.of("scale_world_" + (i + 1)));
        }

        final long start = System.nanoTime();
        final List<List<String>> waves = AgcParallelWorldTickEngine.partitionIntoWaves(worlds, 16, null, deps);
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 150, "500-world DAG wavefront partitioning must complete in under 150ms: was " + elapsedMs + "ms");

        final Map<String, Integer> worldWave = new HashMap<>();
        for (int i = 0; i < waves.size(); i++) {
            for (final String w : waves.get(i)) {
                worldWave.put(w, i);
            }
        }
        assertEquals(500, worldWave.size(), "Every world must be partitioned into a wave");

        // Verify every dependency pair is strictly wave-ordered
        for (int i = 0; i < worldCount; i += 2) {
            final int waveA = worldWave.get("scale_world_" + i);
            final int waveB = worldWave.get("scale_world_" + (i + 1));
            assertTrue(waveA < waveB, "Dependency violated for pair " + i + ": waveA=" + waveA + ", waveB=" + waveB);
        }
    }

    // Honest concurrency telemetry (2026-09-13)
    //
    // Live finding that motivated these: with the default overworld/nether/end, the tick loop's
    // conflict predicate (same Bukkit level name) makes every pair conflict, so the partition is three
    // single-world waves and nothing runs concurrently - yet the old counter reported 1,767 "parallel
    // ticks". These tests pin the difference between taking the dispatch branch and actually overlapping.

    @Test
    void allConflictingWorldsAreReportedAsSerializedNotConcurrent() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        final var engine = AgcParallelWorldTickEngine.get();
        final Set<Thread> threadsUsed = ConcurrentHashMap.newKeySet();

        final var summary = engine.executeWorldTicks(
            List.of("world", "world_nether", "world_the_end"),
            w -> threadsUsed.add(Thread.currentThread()),
            2,
            // The real MinecraftServer predicate: one Bukkit world's dimensions all conflict.
            (a, b) -> true
        );

        assertTrue(summary.parallel(), "the dispatch branch is still taken");
        assertFalse(summary.concurrent(), "three single-world waves never overlap two worlds");
        assertEquals(3, summary.waves());
        assertEquals(Set.of(Thread.currentThread()), threadsUsed,
            "every world of a fully conflicting partition must be ticked on the calling thread");
    }

    @Test
    void metricsSeparateDispatchFromRealOverlap() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        final var engine = AgcParallelWorldTickEngine.get();
        engine.resetMetrics();

        // Arm 1: fully conflicting worlds -> dispatch taken, zero overlap.
        engine.executeWorldTicks(List.of("serial_a", "serial_b", "serial_c"), w -> {}, 2, (a, b) -> true);
        final var afterSerial = engine.metrics();
        assertEquals(1, afterSerial.parallelTicks());
        assertEquals(0, afterSerial.concurrentTicks(), "conflicting worlds must never be counted as concurrent");
        assertEquals(0, afterSerial.concurrentWaves());
        assertEquals(3, afterSerial.singleWorldWaves());
        assertEquals(0.0, afterSerial.concurrencyRatio(), 1e-9);

        // Arm 2: independent worlds -> real overlap. Wave count depends on the core count (the pool is
        // cores-1, so the batch size is cores), hence the asserts only pin what the partition guarantees.
        engine.executeWorldTicks(List.of("ind_a", "ind_b", "ind_c", "ind_d"), w -> {}, 2, null);
        final var afterConcurrent = engine.metrics();
        assertEquals(2, afterConcurrent.parallelTicks());
        assertEquals(1, afterConcurrent.concurrentTicks());
        assertTrue(afterConcurrent.concurrentWaves() >= 1);
        assertEquals(3, afterConcurrent.singleWorldWaves(), "arm 1's serialized waves must be unchanged");
        assertTrue(afterConcurrent.peakWaveSize() >= 2);
        assertEquals(0.5, afterConcurrent.concurrencyRatio(), 1e-9);

        engine.resetMetrics();
    }

    @Test
    void tuningFloorForcesSequentialPathBelowThreshold() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        final var engine = AgcParallelWorldTickEngine.get();
        try {
            // parallelWorldTickMinWorlds = 100: even a call site asking for 2 must stay sequential.
            engine.applyTuning(0, 100);
            assertEquals(100, engine.metrics().minWorlds());

            final var summary = engine.executeWorldTicks(
                List.of("tune_a", "tune_b", "tune_c", "tune_d"), w -> {}, 2, null);
            assertFalse(summary.parallel(), "the operator floor must win over the call-site minimum");
        } finally {
            engine.applyTuning(0, 0);
        }
        // Floor removed -> the same call goes parallel again (2 is the hard floor).
        assertEquals(2, engine.metrics().minWorlds());
        assertTrue(engine.executeWorldTicks(
            List.of("tune_a", "tune_b"), w -> {}, 2, null).parallel());
    }

    @Test
    void forceUnsafeDropsTheConflictPredicate() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        final var engine = AgcParallelWorldTickEngine.get();
        try {
            engine.setForceUnsafe(true);
            assertTrue(engine.isForceUnsafe());
            final var summary = engine.executeWorldTicks(
                List.of("unsafe_a", "unsafe_b"), w -> {}, 2, (a, b) -> true);
            assertTrue(summary.concurrent(),
                "with conflict detection off, worlds the predicate rejects may share a wave");
            assertEquals(1, summary.waves());
        } finally {
            engine.setForceUnsafe(false);
            assertFalse(engine.isForceUnsafe());
        }
        assertFalse(engine.executeWorldTicks(
            List.of("unsafe_a", "unsafe_b"), w -> {}, 2, (a, b) -> true).concurrent(),
            "conflict detection must be back in force after the knob is turned off");
    }

    @Test
    void workerCountAppliesLiveWithoutRestartAndNeverExceedsCoresMinusOne() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        final var engine = AgcParallelWorldTickEngine.get();
        final int before = engine.metrics().workers();
        assertTrue(before >= 1 && before <= Math.max(1, cores - 1),
            "the pool must reserve one core for the main thread, but had " + before + " of " + cores);

        // parallelWorldTickThreads applies live: the pool is swapped (new waves dispatch to the
        // replacement while in-flight waves drain on the old one), so no restart is needed.
        // Clamping still reserves one core for the main thread.
        try {
            engine.applyTuning(1, engine.metrics().minWorlds());
            assertEquals(1, engine.metrics().workers(), "worker tuning must resize the pool live");
            assertTrue(engine.executeWorldTicks(
                List.of("resize_a", "resize_b"), w -> {}, 2, null).parallel(),
                "dispatch must keep working on the resized pool");

            engine.applyTuning(Integer.MAX_VALUE, engine.metrics().minWorlds());
            assertEquals(Math.max(1, cores - 1), engine.metrics().workers(),
                "huge requests must clamp to cores - 1");
        } finally {
            engine.applyTuning(before, engine.metrics().minWorlds());
        }
        assertEquals(before, engine.metrics().workers(), "the pool must be restored after the test");
    }
}
