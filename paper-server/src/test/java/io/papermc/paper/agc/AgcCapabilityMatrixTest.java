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
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        AgcCapabilityMatrix.clearRuntimeOverrides();
    }

    @AfterEach
    void cleanup() {
        AgcCapabilityMatrix.clearRuntimeOverrides();
    }

    @Test
    void vanillaModeDisablesEverything() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.VANILLA);
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            assertFalse(AgcCapabilityMatrix.isEnabled(f),
                "VANILLA mode should disable " + f);
        }
    }

    @Test
    void baselineModeEnablesVanillaSafeAndBaseline() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        // NET_* watermarks and CHUNK_SEND_BUDGET are BASELINE and wired -> enabled in baseline
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_CHANNEL_WATERMARK));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.CHUNK_SEND_BUDGET));
        // NETWORK_READ_TIMEOUT is AGGRESSIVE_BUT_SAFE -> disabled in baseline
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_READ_TIMEOUT));
        // CHUNK_PACKET_CACHE is BASELINE -> enabled by default
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.CHUNK_PACKET_CACHE));
        // NET_ZSTD is AGGRESSIVE_BUT_SAFE -> disabled in baseline
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION));
        // PACKET_PRIORITY is AGGRESSIVE_BUT_SAFE -> disabled in baseline
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_PACKET_PRIORITY));
        // HOT_OBJECT_POOLS is AGGRESSIVE_BUT_SAFE -> disabled in baseline
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.HOT_OBJECT_POOLS));
        // FAST_NETWORK_SERIALIZER is BASELINE -> enabled in baseline
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.FAST_NETWORK_SERIALIZER));
        // VECTOR_MATH_ACCELERATOR is BASELINE -> enabled in baseline
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.VECTOR_MATH_ACCELERATOR));
        // LOCKFREE_EVENT_DISPATCHER is BASELINE -> enabled in baseline
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.LOCKFREE_EVENT_DISPATCHER));
    }

    @Test
    void aggressiveModeEnablesAggressiveFeatures() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            // Dormant or explicitly opt-in features stay disabled in AGGRESSIVE
            if (AgcCapabilityMatrix.isDormant(f)
                || f == AgcCapabilityMatrix.Feature.SINGLEPLAYER_FEEL_COMBAT
                || f == AgcCapabilityMatrix.Feature.NETWORK_READ_TIMEOUT
                || f == AgcCapabilityMatrix.Feature.MULTIWORLD_UNLOAD) {
                assertFalse(AgcCapabilityMatrix.isEnabled(f),
                    "dormant or opt-in feature must stay disabled in AGGRESSIVE: " + f);
            } else {
                assertTrue(AgcCapabilityMatrix.isEnabled(f),
                    "AGGRESSIVE mode should enable " + f);
            }
        }
    }

    @Test
    void runtimeOverrideBeatsMode() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        // DEFAULT_VIEW_DISTANCE is dormant (no production consumer), so mode alone must NOT enable it.
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.DEFAULT_VIEW_DISTANCE));

        // Disable via override
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.DEFAULT_VIEW_DISTANCE, false);
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.DEFAULT_VIEW_DISTANCE));

        // Enable via override (override wins over mode AND dormancy)
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.DEFAULT_VIEW_DISTANCE, true);
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.DEFAULT_VIEW_DISTANCE));
    }

    @Test
    void clearOverrideRestoresModeDefault() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION, true);
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION),
            "operator pin wins over dormancy (verified-live A/B workflow must keep working)");

        AgcCapabilityMatrix.clearRuntimeOverrides();
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION),
            "without the pin, a dormant feature reports disabled in every mode");
    }

    @Test
    void reportContainsModeName() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        final String report = AgcCapabilityMatrix.report();
        assertNotNull(report);
        assertTrue(report.contains("AGC_AGGRESSIVE"), "Report should mention mode");
    }

    @Test
    void nullModeRejected() {
        try {
            AgcCapabilityMatrix.setMode(null);
            org.junit.jupiter.api.Assertions.fail("Expected NullPointerException");
        } catch (final NullPointerException expected) {
            // ok
        }
    }

    @Test
    void nullFeatureReturnsFalse() {
        assertFalse(AgcCapabilityMatrix.isEnabled(null));
    }

    @Test
    void activeBySafetyGroupsCorrectly() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
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
    void modeEnumHasThreeValues() {
        assertEquals(3, AgcCapabilityMatrix.Mode.values().length);
    }

    @Test
    void maxOptimizationBatchGating() {
        // Baseline-safe engines are on by default; aggressive-only ones stay gated.
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.JIGSAW_BOX_OCTREE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.TEMPLATE_POOL_DEDUP));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.LITHIUM_COLLISION_ENGINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.POI_SEARCH_ENGINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.STRUCTURE_NBT_PRUNER));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.C2ME_CHUNK_PIPELINE));
        // UNIVERSE_NET_ENGINE is aggressive-only -> disabled in baseline
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.UNIVERSE_NET_ENGINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_LIGHT_ENGINE));
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.REGION_TICK_BRIDGE));

        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_LIGHT_ENGINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.REGION_TICK_BRIDGE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.UNIVERSE_NET_ENGINE));

        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.VANILLA);
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.JIGSAW_BOX_OCTREE));
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE));
    }

    /**
     * {@link AgcCapabilityMatrix#isEnabled} is cached into an immutable snapshot because it is
     * read from the hottest paths in the server (worldgen noise, entity tracking, chunk send).
     * Every mutator must therefore republish that snapshot - otherwise a mode switch or an
     * {@code /agc override} would silently stop taking effect.
     */
    @Test
    void cachedSnapshotIsRepublishedOnEveryMutation() {
        final AgcCapabilityMatrix.Feature feature = AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE;

        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.VANILLA);
        assertFalse(AgcCapabilityMatrix.isEnabled(feature), "VANILLA must clear the cached snapshot");

        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        assertTrue(AgcCapabilityMatrix.isEnabled(feature), "baseline must re-enable the feature");

        AgcCapabilityMatrix.setRuntimeOverride(feature, false);
        assertFalse(AgcCapabilityMatrix.isEnabled(feature), "override off must reach the cached snapshot");

        AgcCapabilityMatrix.setRuntimeOverride(feature, true);
        assertTrue(AgcCapabilityMatrix.isEnabled(feature), "override on must reach the cached snapshot");

        AgcCapabilityMatrix.clearRuntimeOverrides();
        assertTrue(AgcCapabilityMatrix.isEnabled(feature), "clearing overrides must restore the mode default");

        // The snapshot must cover every declared feature, not just the one under test.
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            assertEquals(AgcCapabilityMatrix.activeBySafety().values().stream().anyMatch(list -> list.contains(f)),
                AgcCapabilityMatrix.isEnabled(f), "cached snapshot disagrees with report for " + f);
        }
    }

    /**
     * Hot-path readers run on worldgen worker threads while the main thread may switch the mode.
     * Pre-caching must not introduce a data race: a read has to observe a complete snapshot, never
     * a torn/garbage one.
     */
    @Test
    void cachedReadsAreSafeUnderConcurrentModeSwitching() throws Exception {
        final AgcCapabilityMatrix.Feature feature = AgcCapabilityMatrix.Feature.HOPPER_OPTIMIZER;
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        final Thread[] readers = new Thread[4];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        // HOPPER_OPTIMIZER is NMS-wired and enabled in both modes, so a correct
                        // (non-torn) snapshot is always true. A dormant feature could not serve here:
                        // its override-free value is false by design.
                        if (!AgcCapabilityMatrix.isEnabled(feature)) {
                            throw new AssertionError("torn snapshot: feature lost while it is enabled in both modes");
                        }
                    }
                } catch (final Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "agc-capability-reader-" + i);
            readers[i].setDaemon(true);
            readers[i].start();
        }

        for (int i = 0; i < 20_000; i++) {
            AgcCapabilityMatrix.setMode(i % 2 == 0
                ? AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE
                : AgcCapabilityMatrix.Mode.AGC_BASELINE);
        }
        stop.set(true);
        for (final Thread reader : readers) {
            reader.join(5_000L);
        }
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        if (failure.get() != null) {
            throw new AssertionError("concurrent read failed", failure.get());
        }
    }
}
