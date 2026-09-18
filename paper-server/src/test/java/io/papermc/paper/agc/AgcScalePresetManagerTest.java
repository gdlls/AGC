package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcScalePresetManagerTest {

    private AgcScalePresetManager manager;

    @BeforeEach
    public void setup() {
        this.manager = AgcScalePresetManager.get();
    }

    @Test
    public void testCompactPresetApplication() {
        final var cfg = this.manager.applyPreset(AgcScalePresetManager.PresetType.COMPACT_EDGE);
        assertNotNull(cfg);
        assertEquals(AgcScalePresetManager.PresetType.COMPACT_EDGE, cfg.type());
        assertEquals(2, cfg.worldTickWorkers());
        assertEquals(8, cfg.defaultViewDistance());
        assertTrue(cfg.enableParallelWorldTick());
        assertTrue(cfg.enableSingleplayerCombat());
    }

    @Test
    public void testStandardPresetApplication() {
        final var cfg = this.manager.applyPreset(AgcScalePresetManager.PresetType.STANDARD_SERVER);
        assertNotNull(cfg);
        assertEquals(AgcScalePresetManager.PresetType.STANDARD_SERVER, cfg.type());
        assertEquals(4, cfg.worldTickWorkers());
        assertEquals(10, cfg.defaultViewDistance());
    }

    @Test
    public void testMassivePresetApplication() {
        final var cfg = this.manager.applyPreset(AgcScalePresetManager.PresetType.MASSIVE_ENTERPRISE);
        assertNotNull(cfg);
        assertEquals(AgcScalePresetManager.PresetType.MASSIVE_ENTERPRISE, cfg.type());
        assertEquals(8, cfg.worldTickWorkers());
        assertEquals(12, cfg.defaultViewDistance());
    }

    @Test
    public void testAutoPresetResolution() {
        final var cfg = this.manager.applyPreset(AgcScalePresetManager.PresetType.AUTO);
        assertNotNull(cfg);
        assertTrue(cfg.worldTickWorkers() >= 2);
        assertTrue(cfg.defaultViewDistance() >= 8);
    }

    @Test
    public void testYamlConfigGeneration() {
        final String yaml = this.manager.generateYamlConfig();
        assertNotNull(yaml);
        assertTrue(yaml.contains("preset:"));
        assertTrue(yaml.contains("parallel-world-tick:"));
        assertTrue(yaml.contains("singleplayer-feel: true"));
    }
}
