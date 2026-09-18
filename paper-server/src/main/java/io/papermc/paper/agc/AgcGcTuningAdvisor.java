package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * AGC — Intelligent JVM & GC Tuning Advisor.
 *
 * <p>Analyzes host topology (cores, NUMA, RAM tier, architecture) and generates optimized
 * zero-pause GC flags (Generational ZGC / G1GC) tailored for ultra-scale Minecraft server workloads.</p>
 */
public final class AgcGcTuningAdvisor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcGcTuningAdvisor.class);
    private static final AgcGcTuningAdvisor INSTANCE = new AgcGcTuningAdvisor();

    public static AgcGcTuningAdvisor get() {
        return INSTANCE;
    }

    private AgcGcTuningAdvisor() {}

    public record GcRecommendation(
        String gcEngine,
        String heapSizing,
        List<String> jvmFlags,
        String explanation
    ) {}

    /**
     * Generates optimal JVM and GC arguments based on active hardware topology.
     */
    public GcRecommendation generateRecommendation() {
        final var profile = AgcHardwareTopologyDetector.get().profile();
        final int logicalCores = profile.logicalCores();
        final long maxMemBytes = profile.maxMemoryBytes();
        final long memGb = Math.max(4, maxMemBytes / (1024L * 1024L * 1024L));

        final List<String> flags = new ArrayList<>();
        final String gcEngine = "Generational ZGC (Zero-Pause < 1ms GC)";
        final String heapSizing;

        // Base ZGC Configuration
        flags.add("-XX:+UseZGC");
        flags.add("-XX:+ZGenerational");

        if (memGb <= 8) {
            // Compact Edge (<= 8GB)
            heapSizing = "-Xms4G -Xmx4G -XX:SoftMaxHeapSize=3500M";
            flags.add("-Xms4G");
            flags.add("-Xmx4G");
            flags.add("-XX:SoftMaxHeapSize=3500M");
            flags.add("-XX:ConcGCThreads=" + Math.max(1, logicalCores / 4));
        } else if (memGb <= 16) {
            // Standard Server (16GB)
            heapSizing = "-Xms8G -Xmx8G -XX:SoftMaxHeapSize=7G";
            flags.add("-Xms8G");
            flags.add("-Xmx8G");
            flags.add("-XX:SoftMaxHeapSize=7G");
            flags.add("-XX:ConcGCThreads=" + Math.max(2, logicalCores / 4));
        } else if (memGb <= 32) {
            // Massive Enterprise (32GB)
            heapSizing = "-Xms16G -Xmx16G -XX:SoftMaxHeapSize=14G";
            flags.add("-Xms16G");
            flags.add("-Xmx16G");
            flags.add("-XX:SoftMaxHeapSize=14G");
            flags.add("-XX:ConcGCThreads=" + Math.max(4, logicalCores / 4));
        } else {
            // Extreme Mega-Scale (64GB+)
            heapSizing = "-Xms32G -Xmx32G -XX:SoftMaxHeapSize=28G";
            flags.add("-Xms32G");
            flags.add("-Xmx32G");
            flags.add("-XX:SoftMaxHeapSize=28G");
            flags.add("-XX:ConcGCThreads=" + Math.max(8, logicalCores / 4));
        }

        // Performance & Vector Flags
        flags.add("-XX:+UnlockDiagnosticVMOptions");
        flags.add("-XX:+AlwaysPreTouch");
        flags.add("-XX:+UseNUMA");
        flags.add("-Dusing.aikars.flags=https://mcflags.emc.gs");
        flags.add("-Dpaper.playerconnection.keepalive=30");

        final String explanation = String.format(
            "Configured for %d cores (%s), %d GB host RAM with Generational ZGC for <1ms pause times.",
            logicalCores, profile.arch(), memGb
        );

        return new GcRecommendation(gcEngine, heapSizing, flags, explanation);
    }

    /**
     * Renders a human-readable diagnostic report of GC recommendations.
     */
    public String renderReport() {
        final GcRecommendation rec = generateRecommendation();
        final StringBuilder sb = new StringBuilder();
        sb.append("=== AGC Intelligent JVM & GC Tuning Advisor ===\n");
        sb.append("  GC Engine        : ").append(rec.gcEngine()).append("\n");
        sb.append("  Heap Allocation  : ").append(rec.heapSizing()).append("\n");
        sb.append("  Hardware Context : ").append(rec.explanation()).append("\n");
        sb.append("  Recommended JVM Startup Arguments:\n");
        for (final String flag : rec.jvmFlags()) {
            sb.append("    ").append(flag).append("\n");
        }
        sb.append("===============================================");
        return sb.toString();
    }
}
