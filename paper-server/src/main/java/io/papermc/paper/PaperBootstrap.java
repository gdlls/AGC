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
        io.papermc.paper.agc.AgcFoliaTuning.bootstrap();
        Runtime.getRuntime().addShutdownHook(new Thread(io.papermc.paper.agc.AgcFoliaTuning::shutdown, "AGC-Shutdown-Hook"));
        io.papermc.paper.agc.AgcNetworkEnhancer.get().setEnabled(true);
        io.papermc.paper.network.ChannelInitializeListenerHolder.addListener(
            net.kyori.adventure.key.Key.key("agc", "network_enhancer"),
            io.papermc.paper.agc.AgcNetworkEnhancer.get()
        );
        LOGGER.info("AGC Performance Layer initialized successfully (mode: {})", io.papermc.paper.agc.AgcCapabilityMatrix.getMode());
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
