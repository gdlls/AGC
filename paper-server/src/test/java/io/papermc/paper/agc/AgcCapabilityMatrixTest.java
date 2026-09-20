package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgcCapabilityMatrix} mode gating.
 */
class AgcCapabilityMatrixTest {

    @BeforeEach
    void resetState() {
        AgcCapabilityMatrix.clearRuntimeOverrides();
    }

    @AfterEach
    void cleanup() {
        AgcCapabilityMatrix.clearRuntimeOverrides();
    }

    @Test
    void allValidOptimizationsEnabledByDefault() {
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            if (AgcCapabilityMatrix.isDormant(f)) {
                assertFalse(AgcCapabilityMatrix.isEnabled(f),
                    "Dormant feature must stay disabled by default: " + f);
            } else {
                assertTrue(AgcCapabilityMatrix.isEnabled(f),
                    "Optimization feature should be enabled by default: " + f);
            }
        }
    }

    @Test
    void runtimeOverrideBeatsDefault() {
        // DEFAULT_VIEW_DISTANCE is dormant, so default must NOT enable it.
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.DEFAULT_VIEW_DISTANCE));

        // Disable via override
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.DEFAULT_VIEW_DISTANCE, false);
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.DEFAULT_VIEW_DISTANCE));

        // Enable via override (override wins over dormancy)
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.DEFAULT_VIEW_DISTANCE, true);
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.DEFAULT_VIEW_DISTANCE));
    }

    @Test
    void clearOverrideRestoresDefault() {
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION, true);
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION),
            "operator pin wins over dormancy (verified-live A/B workflow must keep working)");

        AgcCapabilityMatrix.clearRuntimeOverrides();
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION),
            "without the pin, a dormant feature reports disabled");
    }

    @Test
    void reportContainsActiveStatus() {
        final String report = AgcCapabilityMatrix.report();
        assertNotNull(report);
        assertTrue(report.contains("All Optimizations Active"), "Report should state all optimizations active");
    }

    @Test
    void nullFeatureReturnsFalse() {
        assertFalse(AgcCapabilityMatrix.isEnabled(null));
    }

    @Test
    void activeBySafetyGroupsCorrectly() {
        final var grouped = AgcCapabilityMatrix.activeBySafety();
        assertNotNull(grouped);
        assertTrue(grouped.containsKey(AgcPerformanceTuning.Safety.VANILLA_SAFE));
        assertTrue(grouped.containsKey(AgcPerformanceTuning.Safety.BASELINE));
        assertTrue(grouped.containsKey(AgcPerformanceTuning.Safety.AGGRESSIVE_BUT_SAFE));
        for (final var list : grouped.values()) {
            assertNotNull(list);
        }
    }

    @Test
    void featureHasDescriptionAndSafety() {
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            assertNotNull(f.description());
            assertFalse(f.description().isEmpty());
            assertNotNull(f.safety());
        }
    }

    @Test
    void modeEnumUnifiedToSingleMode() {
        assertEquals(1, AgcCapabilityMatrix.Mode.values().length);
        assertEquals(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE, AgcCapabilityMatrix.getMode());
    }

    @Test
    void maxOptimizationBatchActiveByDefault() {
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.JIGSAW_BOX_OCTREE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.TEMPLATE_POOL_DEDUP));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.LITHIUM_COLLISION_ENGINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.POI_SEARCH_ENGINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.STRUCTURE_NBT_PRUNER));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.C2ME_CHUNK_PIPELINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_LIGHT_ENGINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.REGION_TICK_BRIDGE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.UNIVERSE_NET_ENGINE));
    }

    /**
     * {@link AgcCapabilityMatrix#isEnabled} is cached into an immutable snapshot because it is
     * read from the hottest paths in the server (worldgen noise, entity tracking, chunk send).
     * Every mutator must therefore republish that snapshot.
     */
    @Test
    void cachedSnapshotIsRepublishedOnEveryMutation() {
        final AgcCapabilityMatrix.Feature feature = AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE;

        assertTrue(AgcCapabilityMatrix.isEnabled(feature), "feature is enabled by default");

        AgcCapabilityMatrix.setRuntimeOverride(feature, false);
        assertFalse(AgcCapabilityMatrix.isEnabled(feature), "override off must reach the cached snapshot");

        AgcCapabilityMatrix.setRuntimeOverride(feature, true);
        assertTrue(AgcCapabilityMatrix.isEnabled(feature), "override on must reach the cached snapshot");

        AgcCapabilityMatrix.clearRuntimeOverrides();
        assertTrue(AgcCapabilityMatrix.isEnabled(feature), "clearing overrides must restore default");

        // The snapshot must cover every declared feature, not just the one under test.
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            assertEquals(AgcCapabilityMatrix.activeBySafety().values().stream().anyMatch(list -> list.contains(f)),
                AgcCapabilityMatrix.isEnabled(f), "cached snapshot disagrees with report for " + f);
        }
    }

    /**
     * Hot-path readers run on worldgen worker threads while the main thread may mutate overrides.
     * Pre-caching must not introduce a data race: a read has to observe a complete snapshot, never
     * a torn/garbage one.
     */
    @Test
    void cachedReadsAreSafeUnderConcurrentOverrides() throws Exception {
        final AgcCapabilityMatrix.Feature feature = AgcCapabilityMatrix.Feature.HOPPER_OPTIMIZER;
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        final Thread[] readers = new Thread[4];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        // HOPPER_OPTIMIZER is NMS-wired and enabled by default.
                        AgcCapabilityMatrix.isEnabled(feature);
                    }
                } catch (final Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "agc-capability-reader-" + i);
            readers[i].setDaemon(true);
            readers[i].start();
        }

        for (int i = 0; i < 20_000; i++) {
            AgcCapabilityMatrix.setRuntimeOverride(feature, i % 2 == 0);
        }
        stop.set(true);
        for (final Thread reader : readers) {
            reader.join(5_000L);
        }
        AgcCapabilityMatrix.clearRuntimeOverrides();
        if (failure.get() != null) {
            throw new AssertionError("concurrent read failed", failure.get());
        }
    }
}
