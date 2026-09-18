package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AGC — Dynamic Deployment Scale Preset Manager.
 *
 * <p>Provides standardized, fine-tuned performance presets tailored for different hardware tiers:
 * from low-spec 4-core / 8GB VPS instances to 128+ core / 1.5TB bare-metal enterprise servers.</p>
 */
public final class AgcScalePresetManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcScalePresetManager.class);
    private static final AgcScalePresetManager INSTANCE = new AgcScalePresetManager();

    public enum PresetType {
        /** <= 4 Cores, <= 8GB RAM (VPS, Raspberry Pi, Compact Edge) */
        COMPACT_EDGE("Compact Edge (<=4 Cores, <=8GB RAM)"),
        /** 8 Cores, 16GB RAM (Standard Dedicated / Survival Server) */
        STANDARD_SERVER("Standard Server (8 Cores, 16GB RAM)"),
        /** 16-32 Cores, 32-64GB RAM (Massive Hub & Minigames Network) */
        MASSIVE_ENTERPRISE("Massive Enterprise (16-32 Cores, 32-64GB RAM)"),
        /** 64-128+ Cores, 128GB-1.5TB RAM (Extreme Mega-Scale Dual NUMA EPYC) */
        EXTREME_MEGA_SCALE("Extreme Mega-Scale (64-128+ Cores, 128GB+ RAM)"),
        /** Automatically selects best preset based on hardware detection */
        AUTO("Automatic Hardware-Adaptive Preset");

        private final String description;

        PresetType(final String description) {
            this.description = description;
        }

        public String description() {
            return this.description;
        }
    }

    public record PresetConfig(
        PresetType type,
        int worldTickWorkers,
        int defaultViewDistance,
        int minViewDistance,
        int maxViewDistance,
        long warmGraceTicks,
        long coldGraceTicks,
        boolean enableParallelWorldTick,
        boolean enableSingleplayerCombat,
        boolean enableDirectIoStorage,
        boolean enableFlushCoalescing,
        boolean enableZeroCopyBroadcast,
        boolean enableSpineLeafFanout,
        boolean enablePanamaOffHeap,
        boolean enableSimdCollisions,
        boolean enableSoaPhysics
    ) {}

    private final Map<PresetType, PresetConfig> presets = new EnumMap<>(PresetType.class);
    private final AtomicReference<PresetType> activePresetType = new AtomicReference<>(PresetType.AUTO);
    private final AtomicReference<PresetConfig> activeConfig;

    public static AgcScalePresetManager get() {
        return INSTANCE;
    }

    private AgcScalePresetManager() {
        initPresets();
        final PresetConfig resolved = resolveConfig(PresetType.AUTO);
        this.activeConfig = new AtomicReference<>(resolved);
        LOGGER.info("[AGC] Scale Preset Manager initialized. Active Preset: {} (Workers: {}, View: {})",
            resolved.type(), resolved.worldTickWorkers(), resolved.defaultViewDistance());
    }

    private void initPresets() {
        // 1. Compact Edge (4 Cores / 8GB RAM)
        this.presets.put(PresetType.COMPACT_EDGE, new PresetConfig(
            PresetType.COMPACT_EDGE,
            2, 8, 4, 10,
            100L, 6000L,
            true, true, true, true, true, true, true, true, true
        ));

        // 2. Standard Server (8 Cores / 16GB RAM)
        this.presets.put(PresetType.STANDARD_SERVER, new PresetConfig(
            PresetType.STANDARD_SERVER,
            4, 10, 6, 16,
            200L, 12000L,
            true, true, true, true, true, true, true, true, true
        ));

        // 3. Massive Enterprise (16-32 Cores / 32-64GB RAM)
        this.presets.put(PresetType.MASSIVE_ENTERPRISE, new PresetConfig(
            PresetType.MASSIVE_ENTERPRISE,
            8, 12, 8, 20,
            400L, 24000L,
            true, true, true, true, true, true, true, true, true
        ));

        // 4. Extreme Mega-Scale (64-128+ Cores / 128GB+ RAM)
        this.presets.put(PresetType.EXTREME_MEGA_SCALE, new PresetConfig(
            PresetType.EXTREME_MEGA_SCALE,
            16, 16, 10, 32,
            600L, 36000L,
            true, true, true, true, true, true, true, true, true
        ));
    }

    /**
     * Applies a deployment preset and tunes AGC sub-engines accordingly.
     *
     * @param type The preset type to apply
     * @return The resulting {@link PresetConfig}
     */
    public PresetConfig applyPreset(final PresetType type) {
        final PresetType chosen = type != null ? type : PresetType.AUTO;
        this.activePresetType.set(chosen);
        final PresetConfig resolved = resolveConfig(chosen);
        this.activeConfig.set(resolved);

        // Hardware presets must not override gameplay semantics. In particular,
        // singleplayer-feel combat is AGC_AGGRESSIVE-only and is controlled by the
        // capability matrix or an explicit operator command, not by AUTO hardware
        // detection. Flush coalescing remains a packet-order-preserving transport
        // optimization used by the existing Connection integration.
        AgcFlushCoalescer.get().setEnabled(resolved.enableFlushCoalescing());

        LOGGER.info("[AGC] Applied Preset '{}' -> Resolved: {} (Workers={}, View={})",
            chosen, resolved.type(), resolved.worldTickWorkers(), resolved.defaultViewDistance());

        AgcStabilityJournal.get().record(
            AgcStabilityJournal.EventType.PROFILE_CHANGE,
            "PresetManager",
            "Applied scale preset: " + resolved.type() + " (" + resolved.worldTickWorkers() + " workers)"
        );

        return resolved;
    }

    public PresetConfig getActiveConfig() {
        return this.activeConfig.get();
    }

    public PresetType getActivePresetType() {
        return this.activePresetType.get();
    }

    private PresetConfig resolveConfig(final PresetType requested) {
        if (requested != PresetType.AUTO) {
            return this.presets.getOrDefault(requested, this.presets.get(PresetType.STANDARD_SERVER));
        }

        final var profile = AgcHardwareTopologyDetector.get().profile();
        final int cores = profile.logicalCores();
        final long memGb = profile.maxMemoryBytes() / (1024L * 1024L * 1024L);

        if (cores <= 4 || memGb <= 8) {
            return this.presets.get(PresetType.COMPACT_EDGE);
        } else if (cores <= 12 || memGb <= 24) {
            return this.presets.get(PresetType.STANDARD_SERVER);
        } else if (cores <= 48 || memGb <= 96) {
            return this.presets.get(PresetType.MASSIVE_ENTERPRISE);
        } else {
            return this.presets.get(PresetType.EXTREME_MEGA_SCALE);
        }
    }

    /**
     * Generates a complete YAML string representation for agc.yml.
     */
    public String generateYamlConfig() {
        final PresetConfig cfg = getActiveConfig();
        return String.format(
            "# AGC — High-Performance Multi-Scale Server Configuration\n" +
            "preset: %s\n" +
            "\n" +
            "performance:\n" +
            "  parallel-world-tick:\n" +
            "    enabled: %b\n" +
            "    workers: %d\n" +
            "  view-distance:\n" +
            "    default: %d\n" +
            "    adaptive-min: %d\n" +
            "    adaptive-max: %d\n" +
            "  hibernation:\n" +
            "    warm-grace-ticks: %d\n" +
            "    cold-grace-ticks: %d\n" +
            "  combat:\n" +
            "    singleplayer-feel: %b\n" +
            "  network:\n" +
            "    flush-coalescing: %b\n" +
            "    zero-copy-broadcast: %b\n" +
            "    spine-leaf-fanout: %b\n" +
            "  storage:\n" +
            "    direct-io: %b\n" +
            "    panama-off-heap: %b\n" +
            "  physics:\n" +
            "    simd-collision: %b\n" +
            "    soa-physics: %b\n",
            this.activePresetType.get().name().toLowerCase(),
            cfg.enableParallelWorldTick(),
            cfg.worldTickWorkers(),
            cfg.defaultViewDistance(),
            cfg.minViewDistance(),
            cfg.maxViewDistance(),
            cfg.warmGraceTicks(),
            cfg.coldGraceTicks(),
            cfg.enableSingleplayerCombat(),
            cfg.enableFlushCoalescing(),
            cfg.enableZeroCopyBroadcast(),
            cfg.enableSpineLeafFanout(),
            cfg.enableDirectIoStorage(),
            cfg.enablePanamaOffHeap(),
            cfg.enableSimdCollisions(),
            cfg.enableSoaPhysics()
        );
    }
}
