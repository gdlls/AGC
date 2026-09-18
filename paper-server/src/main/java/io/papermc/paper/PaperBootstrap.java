package io.papermc.paper;

import java.util.List;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        SharedConstants.tryDetectVersion();

        getStartupVersionMessages().forEach(LOGGER::info);

        // AGC start - bootstrap
        final List<String> tuningIssues = io.papermc.paper.agc.AgcPerformanceTuning.validate();
        if (!tuningIssues.isEmpty()) {
            LOGGER.error("AGC Performance Tuning validation failed with {} issue(s):", tuningIssues.size());
            for (final String issue : tuningIssues) {
                LOGGER.error("  - {}", issue);
            }
            throw new IllegalStateException("AGC Performance Tuning validation failed");
        }
        final io.papermc.paper.agc.AgcHardwareTopologyDetector.HardwareProfile profile =
            io.papermc.paper.agc.AgcHardwareTopologyDetector.get().profile();
        io.papermc.paper.agc.AgcScalePresetManager.get();
        io.papermc.paper.agc.AgcPluginSafetyGuard.get().bindPrimaryThread(Thread.currentThread());
        io.papermc.paper.agc.AgcFoliaTuning.bootstrap();
        io.papermc.paper.agc.AgcSingleplayerFeelCombatEngine.getInstance();
        io.papermc.paper.agc.selfhealing.AgcSelfHealingEngine.get();
        io.papermc.paper.agc.AgcHotPathRuntimeBridge.get();
        // AGC start - max-optimization engine bootstrap (pools + stateless singletons)
        io.papermc.paper.agc.light.AgcParallelLightEngine.get().bootstrap();
        io.papermc.paper.agc.chunk.AgcC2meChunkPipeline.get().bootstrap();
        io.papermc.paper.agc.tick.AgcRegionTickBridge.get().bootstrap();
        io.papermc.paper.agc.worldgen.AgcJigsawBoxOctree.shared();
        io.papermc.paper.agc.worldgen.AgcTemplatePoolDedup.get();
        io.papermc.paper.agc.worldgen.AgcStructureNbtPruner.get();
        io.papermc.paper.agc.entity.AgcLithiumCollisionEngine.get();
        io.papermc.paper.agc.entity.AgcPoiSearchEngine.get();
        io.papermc.paper.agc.network.AgcUniverseNetEngine.get();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            io.papermc.paper.agc.light.AgcParallelLightEngine.get().shutdown();
            io.papermc.paper.agc.chunk.AgcC2meChunkPipeline.get().shutdown();
            io.papermc.paper.agc.tick.AgcRegionTickBridge.get().shutdown();
        }, "AGC-Engines-Shutdown-Hook"));
        // AGC end - max-optimization engine bootstrap
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            io.papermc.paper.agc.AgcFoliaTuning.shutdown();
            io.papermc.paper.agc.io.AgcRegionFileManager.get().clear();
        }, "AGC-Shutdown-Hook"));
        io.papermc.paper.agc.AgcNetworkEnhancer.get().setEnabled(true);
        io.papermc.paper.network.ChannelInitializeListenerHolder.addListener(
            net.kyori.adventure.key.Key.key("agc", "network_enhancer"),
            io.papermc.paper.agc.AgcNetworkEnhancer.get()
        );
        io.papermc.paper.agc.AgcStabilityJournal.get().record(
            io.papermc.paper.agc.AgcStabilityJournal.EventType.SYSTEM_INFO,
            "Bootstrap",
            "Server Bootstrap Complete: Cores=" + profile.logicalCores() + ", SIMD=" + profile.simd()
        );
        LOGGER.info("AGC Performance Layer initialized successfully (HW: {}/{} cores, SIMD: {})",
            profile.arch(), profile.logicalCores(), profile.simd());
        // AGC end

        Main.main(options);
    }

    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format(
                "Running Java %s (%s %s; %s %s) on %s %s (%s)",
                javaSpecVersion,
                javaVmName,
                javaVmVersion,
                javaVendor,
                javaVendorVersion,
                osName,
                osVersion,
                osArch
            ),
            String.format(
                "Loading %s %s for Minecraft %s",
                bi.brandName(),
                bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                bi.minecraftVersionId()
            )
        );
    }
}
