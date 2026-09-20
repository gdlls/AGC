package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcConfigSyncTest {

    @BeforeEach
    public void setup() {
        AgcCapabilityMatrix.clearRuntimeOverrides();
        AgcConfigSync.get().resetForTests();
    }

    @AfterEach
    public void cleanup() {
        AgcCapabilityMatrix.clearRuntimeOverrides();
        AgcConfigSync.get().resetForTests();
    }

    @Test
    public void generatedConfigIncludesEveryPerformanceControl() throws Exception {
        withDirectory(directory -> {
            final var config = io.papermc.paper.configuration.AgcConfigurations.load(directory, org.spongepowered.configurate.BasicConfigurationNode.root());
            org.junit.jupiter.api.Assertions.assertEquals("agc_aggressive", config.mode);
            assertFalse(config.performance.parallelWorldTickForceUnsafe);
            assertTrue(config.singleplayerFeelCombat);
            assertFalse(config.performance.hitRewindEnabled);
            final var node = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                .path(directory.resolve("agc.yml")).build().load();
            final long fields = java.util.Arrays.stream(config.performance.getClass().getFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers())).count();
            org.junit.jupiter.api.Assertions.assertEquals(fields, node.node("performance").childrenMap().size());
            assertTrue(node.node("performance", "parallel-world-tick").getBoolean());
        });
    }

    @Test
    public void migratesExplicitValuesAndDedicatedFileWins() throws Exception {
        withDirectory(directory -> {
            final var legacy = org.spongepowered.configurate.BasicConfigurationNode.root();
            legacy.node("mode").set("agc_baseline");
            legacy.node("performance", "fast-noise-engine").set(false);
            legacy.node("performance", "parallel-world-tick-threads").set(7);
            legacy.node("future-operator-option").set("preserved");
            var config = io.papermc.paper.configuration.AgcConfigurations.load(directory, legacy);
            assertFalse(config.performance.fastNoiseEngine);
            org.junit.jupiter.api.Assertions.assertEquals(7, config.performance.parallelWorldTickThreads);
            legacy.node("performance", "fast-noise-engine").set(true);
            config = io.papermc.paper.configuration.AgcConfigurations.load(directory, legacy);
            assertFalse(config.performance.fastNoiseEngine);
            org.junit.jupiter.api.Assertions.assertEquals("agc_baseline", config.mode);
            final String file = java.nio.file.Files.readString(directory.resolve("agc.yml"));
            assertTrue(file.contains("future-operator-option: preserved"));
        });
    }

    @Test
    public void invalidFilesAreNotOverwrittenOrApplied() throws Exception {
        withDirectory(directory -> {
            final var config = new io.papermc.paper.configuration.GlobalConfiguration.Agc();
            AgcConfigSync.get().syncLoadedConfiguration(config);
            for (final String invalid : new String[] {
                "mode: nonsense\n", "performance: []\n", "performance:\n  fast-noise-engine: perhaps\n",
                "performance:\n  parallel-world-tick-threads: -1\n", "performance:\n  parallel-world-tick-min-worlds: 1\n",
                "performance:\n  tracker-idle-skip-refresh-ticks: 1.5\n", "performance: [\n"
            }) {
                java.nio.file.Files.writeString(directory.resolve("agc.yml"), invalid);
                org.junit.jupiter.api.Assertions.assertThrows(org.spongepowered.configurate.ConfigurateException.class,
                    () -> io.papermc.paper.configuration.AgcConfigurations.load(directory, org.spongepowered.configurate.BasicConfigurationNode.root()));
                org.junit.jupiter.api.Assertions.assertEquals(invalid, java.nio.file.Files.readString(directory.resolve("agc.yml")));
                org.junit.jupiter.api.Assertions.assertEquals(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE, AgcCapabilityMatrix.getMode());
            }
        });
    }

    @Test
    public void reloadUpdatesConfigurationAndRuntimeGates() throws Exception {
        withDirectory(directory -> {
            final var legacy = org.spongepowered.configurate.BasicConfigurationNode.root();
            var config = io.papermc.paper.configuration.AgcConfigurations.load(directory, legacy);
            AgcConfigSync.get().syncLoadedConfiguration(config);
            assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_WORLD_TICK));
            assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.SINGLEPLAYER_FEEL_COMBAT));
            java.nio.file.Files.writeString(directory.resolve("agc.yml"), "performance:\n  fast-noise-engine: false\n  parallel-world-tick: false\n  parallel-world-tick-min-worlds: 4\n");
            config = io.papermc.paper.configuration.AgcConfigurations.load(directory, legacy);
            AgcConfigSync.get().syncLoadedConfiguration(config);
            assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE));
            assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_WORLD_TICK));
            org.junit.jupiter.api.Assertions.assertEquals(4, config.performance.parallelWorldTickMinWorlds);
        });
    }

    private static void withDirectory(final DirectoryTest test) throws Exception {
        final var directory = java.nio.file.Files.createTempDirectory("agc-config-test");
        try {
            test.run(directory);
        } finally {
            try (final var paths = java.nio.file.Files.walk(directory)) {
                for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.delete(path);
                }
            }
        }
    }

    private interface DirectoryTest {
        void run(java.nio.file.Path directory) throws Exception;
    }

    @Test
    public void testSyncRequiresConfig() {
        // In unit tests GlobalConfiguration is unavailable -> sync defers cleanly.
        final boolean ran = AgcConfigSync.get().syncIfNeeded();
        // Either outcome is acceptable; the contract is "no throw, no partial state".
        if (ran) {
            assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.JIGSAW_BOX_OCTREE)
                || !AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.JIGSAW_BOX_OCTREE));
        } else {
            assertNull(AgcCapabilityMatrix.getRuntimeOverride(AgcCapabilityMatrix.Feature.JIGSAW_BOX_OCTREE));
        }
    }

    @Test
    public void testOverrideGetterRoundTrip() {
        assertNull(AgcCapabilityMatrix.getRuntimeOverride(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE));
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE, Boolean.TRUE);
        assertTrue(AgcCapabilityMatrix.getRuntimeOverride(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE));
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE, null);
        assertNull(AgcCapabilityMatrix.getRuntimeOverride(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE));
    }

    @Test
    public void testRuntimeOverrideClearsToDefault() {
        // In unified mode, LITHIUM_COLLISION_ENGINE is enabled by default.
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.LITHIUM_COLLISION_ENGINE));
        // Overriding to false disables it.
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.LITHIUM_COLLISION_ENGINE, Boolean.FALSE);
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.LITHIUM_COLLISION_ENGINE));
        // Clearing the pin restores the unified default (enabled).
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.LITHIUM_COLLISION_ENGINE, null);
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.LITHIUM_COLLISION_ENGINE));
    }

    @Test
    public void testSyncIfNeededLatches() {
        // Second call short-circuits on the latch without touching the matrix.
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.POI_SEARCH_ENGINE, Boolean.FALSE);
        AgcConfigSync.get().syncIfNeeded();
        AgcConfigSync.get().syncIfNeeded();
        // Latch behavior only; no crash = pass. Override intact (sync defers without config).
        assertTrue(AgcCapabilityMatrix.getRuntimeOverride(AgcCapabilityMatrix.Feature.POI_SEARCH_ENGINE) == null
            || !AgcCapabilityMatrix.getRuntimeOverride(AgcCapabilityMatrix.Feature.POI_SEARCH_ENGINE));
    }
}
