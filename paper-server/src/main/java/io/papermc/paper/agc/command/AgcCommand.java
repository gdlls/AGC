package io.papermc.paper.agc.command;

import io.papermc.paper.agc.AgcAdaptiveGovernor;
import io.papermc.paper.agc.AgcScalePresetManager;
import io.papermc.paper.agc.AgcWorldHibernationEngine;
import io.papermc.paper.agc.network.AgcConnectionManager;
import io.papermc.paper.agc.plugin.AgcPluginWatchdog;
import io.papermc.paper.agc.profiling.AgcMemoryTracker;
import io.papermc.paper.agc.profiling.AgcTickProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Unified Server Administration & Diagnostics Command Engine.
 *
 * <p>Implements all {@code /agc} administrative subcommands for live performance telemetry,
 * world hibernation analysis, memory inspection, plugin watchdogs, and runtime scaling presets.</p>
 */
public final class AgcCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcCommand.class);
    private static final AgcCommand INSTANCE = new AgcCommand();

    private final AtomicLong commandsExecuted = new AtomicLong();

    public static AgcCommand get() {
        return INSTANCE;
    }

    private AgcCommand() {}

    /**
     * Executes an AGC command line.
     *
     * @param args Command arguments (e.g. ["status"], ["preset", "massive"])
     * @return Formatted command output string
     */
    public String execute(final String[] args) {
        this.commandsExecuted.incrementAndGet();

        if (args == null || args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            return getHelp();
        }

        final String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "status" -> getStatusReport();
            case "worlds" -> getWorldsReport();
            case "network" -> getNetworkReport();
            case "memory" -> getMemoryReport();
            case "plugins" -> getPluginsReport();
            case "preset" -> handlePreset(args);
            case "profile" -> handleProfile(args);
            default -> "§cUnknown AGC subcommand: " + args[0] + ". Type §e/agc help§c for available commands.";
        };
    }

    private String getStatusReport() {
        final var profiler = AgcTickProfiler.get();
        final var mem = AgcMemoryTracker.get();
        final var gov = AgcAdaptiveGovernor.get();
        final var preset = AgcScalePresetManager.get();

        return String.format(
            """
            §6=== [AGC System Status] ===
            §7Rolling MSPT     : §a%.2f ms (P50: %.2f ms, P95: %.2f ms, P99: %.2f ms)
            §7Governor Level   : §e%s
            §7Active Preset    : §b%s
            §7Heap Memory      : §f%.1f / %.1f MB (%.1f%%)
            §7Direct Memory    : §f%.1f MB
            §6===========================""",
            profiler.getRollingMspt(),
            profiler.getP50Mspt(),
            profiler.getP95Mspt(),
            profiler.getP99Mspt(),
            gov.getLevel().name(),
            preset.getActivePresetType().name(),
            mem.getUsedHeapBytes() / (1024.0 * 1024.0),
            mem.getMaxHeapBytes() / (1024.0 * 1024.0),
            mem.getHeapUsageRatio() * 100.0,
            mem.getDirectMemoryUsedBytes() / (1024.0 * 1024.0)
        );
    }

    private String getWorldsReport() {
        final var hib = AgcWorldHibernationEngine.get();
        final var metrics = hib.metrics();

        return String.format(
            """
            §6=== [AGC Worlds & Hibernation] ===
            §7Total Tracked Worlds : §f%d
            §7Active Worlds (HOT)  : §a%d
            §7Sleeping (WARM)      : §e%d
            §7Cold Dormant (COLD)  : §b%d
            §7World Ticks Saved    : §a%d ticks
            §6==================================""",
            metrics.totalTrackedWorlds(),
            metrics.activeWorlds(),
            metrics.warmHibernatingWorlds(),
            metrics.coldDormantWorlds(),
            metrics.worldTicksSaved()
        );
    }

    private String getNetworkReport() {
        final var conn = AgcConnectionManager.get().metrics();
        return String.format(
            """
            §6=== [AGC Network & Connections] ===
            §7Active Connections   : §f%d
            §7Optimal Workers      : §b%d
            §7QoS Packets Limited  : §e%d
            §7Total Managed Bytes  : §a%d bytes
            §6===================================""",
            conn.activeConnections(),
            conn.optimalWorkerThreads(),
            conn.qosThrottledPackets(),
            conn.totalBytesManaged()
        );
    }

    private String getMemoryReport() {
        return AgcMemoryTracker.get().getMemoryReport();
    }

    private String getPluginsReport() {
        return AgcPluginWatchdog.get().generatePluginReport();
    }

    private String handlePreset(final String[] args) {
        if (args.length < 2) {
            return "§cUsage: /agc preset <compact|standard|massive|auto>";
        }
        final String name = args[1].toUpperCase(Locale.ROOT);
        try {
            final AgcScalePresetManager.PresetType p = AgcScalePresetManager.PresetType.valueOf(name);
            AgcScalePresetManager.get().applyPreset(p);
            return "§a[AGC] Successfully applied server scale preset: §b" + p.name();
        } catch (final IllegalArgumentException e) {
            return "§cInvalid preset name: " + args[1] + ". Valid presets: COMPACT_EDGE, STANDARD_SERVER, MASSIVE_ENTERPRISE, AUTO";
        }
    }

    private String handleProfile(final String[] args) {
        if (args.length < 2) {
            return "§cUsage: /agc profile <start|stop|dump>";
        }
        final String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "dump" -> "§a[AGC Profiler Dump]\n" + AgcTickProfiler.get().getReport();
            case "start" -> "§a[AGC] Profiling started.";
            case "stop" -> "§e[AGC] Profiling stopped.";
            default -> "§cUsage: /agc profile <start|stop|dump>";
        };
    }

    private String getHelp() {
        return """
            §6=== [AGC Administrative Commands] ===
            §e/agc status   §7- Displays global MSPT, memory, and governor status
            §e/agc worlds   §7- Displays per-world hibernation metrics
            §e/agc network  §7- Displays Netty workers and QoS metrics
            §e/agc memory   §7- Displays JVM heap, direct memory, and GC statistics
            §e/agc plugins  §7- Displays per-plugin CPU consumption and watchdog status
            §e/agc preset   §7- Dynamically switches hardware scaling preset
            §e/agc profile  §7- Inspects high-resolution tick profiling dumps
            §6=====================================""";
    }

    public void clear() {
        this.commandsExecuted.set(0);
    }

    public long getCommandsExecuted() {
        return this.commandsExecuted.get();
    }
}
