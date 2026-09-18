package io.papermc.paper.command;

import io.papermc.paper.agc.AgcCapabilityMatrix;
import io.papermc.paper.agc.AgcFoliaTuning;
import io.papermc.paper.agc.AgcHotPathCache;
import io.papermc.paper.agc.AgcMetrics;
import io.papermc.paper.agc.AgcNetworkEnhancer;
import io.papermc.paper.agc.AgcPerformanceTuning;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@DefaultQualifier(NonNull.class)
public final class AgcCommand extends Command {

    public static final String BASE_PERM = "bukkit.command.agc";
    private static final List<String> SUBCOMMANDS = List.of(
        "status", "mode", "report", "profile", "memory", "network", "plugins", "entities", "hotspots", "features", "override",
        "benchmark", "journal", "governor", "dashboard", "topology", "combat", "knockback", "export", "preset", "gc",
        "parity", "safety", "worlds", "maxopt", "verify", "help"
    );

    public AgcCommand(final String name) {
        super(name);
        this.description = "AGC High-Performance Engine control & telemetry";
        this.usageMessage = "/agc [status | mode <mode> | knockback | report | features | override <feat> <true|false|reset> | benchmark]";
        this.setPermission(BASE_PERM);

        final org.bukkit.Server server = Bukkit.getServer();
        if (server != null) {
            final PluginManager pluginManager = server.getPluginManager();
            if (pluginManager != null && pluginManager.getPermission(BASE_PERM) == null) {
                pluginManager.addPermission(new Permission(BASE_PERM, "Allows access to AGC commands", PermissionDefault.OP));
            }
        }
    }

    @Override
    public List<String> tabComplete(
        final CommandSender sender,
        final String alias,
        final String[] args,
        final @Nullable Location location
    ) throws IllegalArgumentException {
        if (!sender.hasPermission(BASE_PERM)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            final List<String> matches = new ArrayList<>();
            for (final String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    matches.add(sub);
                }
            }
            return matches;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("knockback") || args[0].equalsIgnoreCase("combat"))) {
            final String prefix = args[1].toLowerCase(Locale.ROOT);
            final List<String> options = List.of("info", "toggle", "offground", "reset");
            final List<String> matches = new ArrayList<>();
            for (final String opt : options) {
                if (opt.startsWith(prefix)) {
                    matches.add(opt);
                }
            }
            return matches;
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("knockback") || args[0].equalsIgnoreCase("combat")) && args[1].equalsIgnoreCase("offground")) {
            return List.of("true", "false");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            final String prefix = args[1].toLowerCase(Locale.ROOT);
            final List<String> matches = new ArrayList<>();
            for (final AgcCapabilityMatrix.Mode mode : AgcCapabilityMatrix.Mode.values()) {
                if (mode.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    matches.add(mode.name().toLowerCase(Locale.ROOT));
                }
            }
            return matches;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("override")) {
            final String prefix = args[1].toUpperCase(Locale.ROOT);
            final List<String> matches = new ArrayList<>();
            for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
                if (f.name().startsWith(prefix)) {
                    matches.add(f.name());
                }
            }
            return matches;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("override")) {
            return List.of("true", "false", "reset");
        }
        return Collections.emptyList();
    }

    @Override
    public boolean execute(
        final CommandSender sender,
        final String commandLabel,
        final String[] args
    ) {
        if (!testPermission(sender)) {
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        final String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> sendStatus(sender);
            case "mode" -> handleMode(sender, args);
            case "report" -> sendReport(sender);
            case "profile" -> sendProfile(sender, args);
            case "memory" -> sendMemory(sender);
            case "entities" -> sendEntities(sender, args);
            case "hotspots" -> sendHotspots(sender, args);
            case "features" -> sendFeatures(sender);
            case "override" -> handleOverride(sender, args);
            case "benchmark" -> runBenchmark(sender);
            case "journal" -> sendJournal(sender, args);
            case "governor" -> handleGovernor(sender, args);
            case "dashboard" -> sendDashboard(sender);
            case "topology" -> sendTopology(sender);
            case "combat", "knockback" -> handleKnockback(sender, args);
            case "export" -> handleExport(sender);
            case "preset" -> handlePreset(sender, args);
            case "gc" -> sendGc(sender);
            case "parity" -> sendParity(sender);
            case "safety" -> sendSafety(sender);
            case "worlds" -> sendWorlds(sender);
            case "maxopt" -> sendMaxOpt(sender);
            case "verify" -> handleVerify(sender, args);
            case "network", "plugins" -> {
                final String out = io.papermc.paper.agc.command.AgcCommand.get().execute(args);
                sender.sendMessage(text(out, GRAY));
            }
            default -> {
                sender.sendMessage(text("Unknown AGC subcommand. Use /agc help", RED));
                return false;
            }
        }
        return true;
    }

    private void sendHelp(final CommandSender sender) {
        sender.sendMessage(text("------------------- AGC Engine Commands -------------------", GOLD, TextDecoration.BOLD));
        sender.sendMessage(text("/agc status ", YELLOW).append(text("- View runtime mode, worker pool, and memory health", GRAY)));
        sender.sendMessage(text("/agc profile [window] ", YELLOW).append(text("- Real-time nanosecond subsystem tick profiler (p50, p95, p99)", GRAY)));
        sender.sendMessage(text("/agc knockback [profile|info|toggle] ", YELLOW).append(text("- Next-Gen PvP knockback, KBSync 2.0 & combat engine", GRAY)));
        sender.sendMessage(text("/agc memory ", YELLOW).append(text("- Real-time JVM heap, off-heap & GC pause telemetry", GRAY)));
        sender.sendMessage(text("/agc entities [limit] ", YELLOW).append(text("- Live entity census with weighted CPU load ranking", GRAY)));
        sender.sendMessage(text("/agc hotspots [limit] ", YELLOW).append(text("- Dense entity chunk hotspot locator", GRAY)));
        sender.sendMessage(text("/agc governor [level] ", YELLOW).append(text("- 4-Stage adaptive performance governor status & override", GRAY)));
        sender.sendMessage(text("/agc mode <mode> ", YELLOW).append(text("- Switch mode (VANILLA, AGC_BASELINE, AGC_AGGRESSIVE)", GRAY)));
        sender.sendMessage(text("/agc preset <preset> ", YELLOW).append(text("- Set scale preset (compact, standard, massive, auto)", GRAY)));
        sender.sendMessage(text("/agc report ", YELLOW).append(text("- Per-world tick latency and load budget report", GRAY)));
        sender.sendMessage(text("/agc features ", YELLOW).append(text("- List all performance features and safety tags", GRAY)));
        sender.sendMessage(text("/agc override <feature> <val> ", YELLOW).append(text("- Override feature state (true/false/reset)", GRAY)));
        sender.sendMessage(text("/agc benchmark ", YELLOW).append(text("- Run real-time micro-benchmark across hot-path caches", GRAY)));
        sender.sendMessage(text("/agc journal [limit] ", YELLOW).append(text("- View recent stability and governor event log", GRAY)));
        sender.sendMessage(text("/agc dashboard ", YELLOW).append(text("- Render complete real-time performance dashboard", GRAY)));
        sender.sendMessage(text("/agc topology ", YELLOW).append(text("- View CPU, NUMA, SIMD, and Network hardware profile", GRAY)));
        sender.sendMessage(text("/agc gc ", YELLOW).append(text("- View JVM & Generational ZGC tuning advisor report", GRAY)));
        sender.sendMessage(text("/agc parity ", YELLOW).append(text("- View vanilla behavioral parity compliance report", GRAY)));
        sender.sendMessage(text("/agc safety ", YELLOW).append(text("- View plugin safety analysis & isolation metrics", GRAY)));
        sender.sendMessage(text("/agc worlds ", YELLOW).append(text("- View multi-world 0ms hibernation state metrics", GRAY)));
        sender.sendMessage(text("/agc export ", YELLOW).append(text("- Export real-time AGC telemetry in JSON format", GRAY)));
        sender.sendMessage(text("/agc maxopt ", YELLOW).append(text("- Max-optimization batch: per-engine live activation & counters", GRAY)));
        sender.sendMessage(text("------------------------------------------------------------", GOLD));
    }

    private void sendTopology(final CommandSender sender) {
        final var prof = io.papermc.paper.agc.AgcHardwareTopologyDetector.get().profile();
        sender.sendMessage(text("=== AGC Hardware Topology Profile ===", AQUA, TextDecoration.BOLD));
        sender.sendMessage(text("CPU Architecture: ", WHITE).append(text(prof.arch().name(), GOLD)));
        sender.sendMessage(text("Operating System: ", WHITE).append(text(prof.os().name(), GOLD)));
        sender.sendMessage(text("Logical / Physical Cores: ", WHITE).append(text(prof.logicalCores() + " logical / " + prof.physicalCoresEstimate() + " physical", GOLD)));
        sender.sendMessage(text("NUMA Domains: ", WHITE).append(text(String.valueOf(prof.numaNodesEstimate()), GOLD)));
        sender.sendMessage(text("SIMD Vector Engine: ", WHITE).append(text(prof.simd().description(), GREEN, TextDecoration.BOLD)));
        sender.sendMessage(text("Network Transport Engine: ", WHITE).append(text(prof.networkBackend().description(), GREEN)));
        sender.sendMessage(text("Memory Tier Profile: ", WHITE).append(text(prof.memoryTier().description() + " (" + (prof.maxMemoryBytes() / (1024L * 1024L * 1024L)) + " GB)", GOLD)));
        sender.sendMessage(text("Recommended Parallel Workers: ", WHITE).append(text("WorldTick=" + prof.recommendedWorldTickWorkers() + ", Netty=" + prof.recommendedNettyWorkers() + ", WorkSteal=" + prof.recommendedWorkStealingWorkers() + ", IO=" + prof.recommendedIoWorkers(), GRAY)));
    }

    private void handleKnockback(final CommandSender sender, final String[] args) {
        final var combat = io.papermc.paper.agc.AgcSingleplayerFeelCombatEngine.getInstance();
        if (args.length < 2 || args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("status")) {
            for (final String line : combat.generateReport().split("\n")) {
                sender.sendMessage(text(line, line.startsWith("=") ? GOLD : line.contains("Active") ? GREEN : WHITE));
            }
            return;
        }

        final String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "toggle" -> {
                final boolean nextState = !combat.isEnabled();
                combat.setEnabled(nextState);
                sender.sendMessage(text("AGC Native KBSync Combat Engine is now: ", GREEN)
                    .append(text(nextState ? "ENABLED (Singleplayer-Feel Active)" : "DISABLED (Vanilla Mode)", nextState ? GOLD : RED, TextDecoration.BOLD)));
            }
            case "offground" -> {
                if (args.length < 3) {
                    sender.sendMessage(text("Off-Ground Sync is currently: ", WHITE)
                        .append(text(combat.isOffGroundSyncEnabled() ? "ENABLED" : "DISABLED", combat.isOffGroundSyncEnabled() ? GREEN : RED)));
                    return;
                }
                final boolean enable = Boolean.parseBoolean(args[2]);
                combat.setOffGroundSyncEnabled(enable);
                sender.sendMessage(text("Set Off-Ground Sync -> ", GREEN).append(text(String.valueOf(enable), GOLD, TextDecoration.BOLD)));
            }
            case "reset" -> {
                combat.clear();
                sender.sendMessage(text("Reset AGC Combat Engine state and telemetry statistics.", GREEN));
            }
            default -> {
                sender.sendMessage(text("Usage: /agc knockback [info | toggle | offground <true|false> | reset]", RED));
            }
        }
    }

    private void handleExport(final CommandSender sender) {
        final var metrics = io.papermc.paper.agc.AgcMetricsExporter.get().exportAll();
        sender.sendMessage(text("=== AGC Telemetry Map Snapshot ===", AQUA, TextDecoration.BOLD));
        metrics.forEach((k, v) -> sender.sendMessage(text("  " + k + ": ", YELLOW).append(text(String.valueOf(v), WHITE))));
    }

    private void handlePreset(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            final var current = io.papermc.paper.agc.AgcScalePresetManager.get().getActiveConfig();
            sender.sendMessage(text("=== AGC Scale Preset Status ===", AQUA, TextDecoration.BOLD));
            sender.sendMessage(text("Active Preset: ", WHITE).append(text(current.type().name(), GOLD, TextDecoration.BOLD)));
            sender.sendMessage(text("Workers: ", WHITE).append(text(String.valueOf(current.worldTickWorkers()), GREEN)));
            sender.sendMessage(text("View Distance: ", WHITE).append(text(current.defaultViewDistance() + " (Adaptive: " + current.minViewDistance() + "-" + current.maxViewDistance() + ")", GREEN)));
            sender.sendMessage(text("Singleplayer Combat: ", WHITE).append(text(current.enableSingleplayerCombat() ? "ENABLED" : "DISABLED", current.enableSingleplayerCombat() ? GREEN : RED)));
            sender.sendMessage(text("Usage: /agc preset <compact | standard | massive | extreme | auto>", GRAY));
            return;
        }

        final String input = args[1].toUpperCase(Locale.ROOT);
        io.papermc.paper.agc.AgcScalePresetManager.PresetType target = null;
        for (final var p : io.papermc.paper.agc.AgcScalePresetManager.PresetType.values()) {
            if (p.name().equals(input) || p.name().replace("_SERVER", "").replace("_ENTERPRISE", "").replace("_SCALE", "").equals(input)) {
                target = p;
                break;
            }
        }

        if (target == null) {
            sender.sendMessage(text("Invalid preset '" + args[1] + "'. Available: compact, standard, massive, extreme, auto", RED));
            return;
        }

        final var applied = io.papermc.paper.agc.AgcScalePresetManager.get().applyPreset(target);
        sender.sendMessage(text("Successfully applied AGC Scale Preset: ", GREEN).append(text(applied.type().name(), GOLD, TextDecoration.BOLD)));
    }

    private void sendGc(final CommandSender sender) {
        final String report = io.papermc.paper.agc.AgcGcTuningAdvisor.get().renderReport();
        for (final String line : report.split("\n")) {
            sender.sendMessage(text(line, line.startsWith("=") ? GOLD : line.contains("Recommended") ? AQUA : line.trim().startsWith("-") ? GREEN : WHITE));
        }
    }

    private void sendParity(final CommandSender sender) {
        final var m = io.papermc.paper.agc.AgcBehaviorParitySuite.get().metrics();
        sender.sendMessage(text("=== AGC Vanilla Behavioral Parity Suite ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(text("Compliance Status: ", WHITE).append(text(m.isFullyCompliant() ? "100% COMPLIANT" : "VIOLATIONS DETECTED", m.isFullyCompliant() ? GREEN : RED, TextDecoration.BOLD)));
        sender.sendMessage(text("Total Rules Checked: ", WHITE).append(text(String.valueOf(m.totalRules()), GOLD)));
        sender.sendMessage(text("Checks Executed: ", WHITE).append(text(String.valueOf(m.checksExecuted()), GOLD)));
        sender.sendMessage(text("Allowed Causal Deviations: ", WHITE).append(text(String.valueOf(m.deviationsAllowed()), GREEN)));
        sender.sendMessage(text("Violations Detected: ", WHITE).append(text(String.valueOf(m.violationsDetected()), m.violationsDetected() == 0 ? GREEN : RED)));
    }

    private void sendSafety(final CommandSender sender) {
        final var scanner = io.papermc.paper.agc.AgcPluginScanner.get();
        final var m = scanner.metrics();
        sender.sendMessage(text("=== AGC Plugin Safety & Isolation Guard ===", AQUA, TextDecoration.BOLD));
        sender.sendMessage(text("Plugins Scanned: ", WHITE).append(text(String.valueOf(m.pluginsScanned()), GOLD)));
        sender.sendMessage(text("Parallel-Safe Plugins: ", WHITE).append(text(String.valueOf(m.parallelSafePlugins()), GREEN)));
        sender.sendMessage(text("Sync-Sensitive Plugins: ", WHITE).append(text(String.valueOf(m.syncSensitivePlugins()), YELLOW)));
        sender.sendMessage(text("Dangerous Plugins (Isolated): ", WHITE).append(text(String.valueOf(m.dangerousPlugins()), m.dangerousPlugins() == 0 ? GREEN : RED)));
    }

    private void sendWorlds(final CommandSender sender) {
        final var hib = io.papermc.paper.agc.AgcWorldHibernationEngine.get();
        final var m = hib.metrics();
        sender.sendMessage(text("=== AGC Multi-World Hibernation Status ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(text("Tracked Worlds: ", WHITE).append(text(String.valueOf(m.totalTrackedWorlds()), GOLD)));
        sender.sendMessage(text("Active (HOT) Worlds: ", WHITE).append(text(String.valueOf(m.activeWorlds()), GREEN)));
        sender.sendMessage(text("Hibernating (WARM) Worlds: ", WHITE).append(text(String.valueOf(m.warmHibernatingWorlds()), YELLOW)));
        sender.sendMessage(text("Deep Cold (COLD) Worlds: ", WHITE).append(text(String.valueOf(m.coldDormantWorlds()), AQUA)));
        sender.sendMessage(text("Total World Ticks Saved: ", WHITE).append(text(String.valueOf(m.worldTicksSaved()), GREEN, TextDecoration.BOLD)));
    }

    private void sendDashboard(final CommandSender sender) {
        final List<String> lines = io.papermc.paper.agc.AgcDashboardRenderer.get().renderDashboard();
        for (final String line : lines) {
            sender.sendMessage(text(line, AQUA));
        }
    }

    private void sendJournal(final CommandSender sender, final String[] args) {
        int limit = 10;
        if (args.length >= 2) {
            try {
                limit = Math.max(1, Integer.parseInt(args[1]));
            } catch (final NumberFormatException ignored) {}
        }
        final List<io.papermc.paper.agc.AgcStabilityJournal.JournalEntry> entries =
            io.papermc.paper.agc.AgcStabilityJournal.get().getRecentEntries(limit);

        sender.sendMessage(text("=== AGC Stability Journal (Last " + entries.size() + " events) ===", GOLD, TextDecoration.BOLD));
        if (entries.isEmpty()) {
            sender.sendMessage(text("No journal events recorded yet.", GRAY));
            return;
        }
        for (final var entry : entries) {
            sender.sendMessage(text(entry.format(), WHITE));
        }
    }

    private void sendProfile(final CommandSender sender, final String[] args) {
        int window = 100;
        if (args.length >= 2) {
            try {
                window = Integer.parseInt(args[1]);
            } catch (final NumberFormatException ignored) {}
        }
        final var profiler = io.papermc.paper.agc.profiling.AgcTickProfiler.get();
        final var summary = profiler.getSummary(window);

        sender.sendMessage(text(String.format("=== AGC Subsystem Profiler (Window: %d ticks) ===", summary.sampleCount()), AQUA, TextDecoration.BOLD));
        sender.sendMessage(text(String.format("MSPT: avg=%.2fms | p50=%.2fms | p95=%.2fms | p99=%.2fms | max=%.2fms",
            summary.averageMspt(), summary.p50Mspt(), summary.p95Mspt(), summary.p99Mspt(), summary.maxMspt()),
            summary.averageMspt() <= 30.0 ? GREEN : summary.averageMspt() <= 45.0 ? YELLOW : RED));

        for (final io.papermc.paper.agc.profiling.AgcTickProfiler.Subsystem sub : io.papermc.paper.agc.profiling.AgcTickProfiler.Subsystem.values()) {
            final var s = summary.subsystemStats().get(sub);
            if (s != null && (s.percentageOfTotal() > 0.1 || s.averageMs() > 0.05)) {
                sender.sendMessage(text(String.format("  %-24s: ", sub.displayName()), WHITE)
                    .append(text(String.format("%5.1f%%", s.percentageOfTotal()), s.percentageOfTotal() > 30.0 ? RED : s.percentageOfTotal() > 15.0 ? YELLOW : GREEN, TextDecoration.BOLD))
                    .append(text(String.format(" (avg: %.2fms, p99: %.2fms, max: %.2fms)", s.averageMs(), s.p99Ms(), s.maxMs()), GRAY)));
            }
        }
    }

    private void sendMemory(final CommandSender sender) {
        final var mem = io.papermc.paper.agc.profiling.AgcMemoryTracker.get();
        for (final String line : mem.getMemoryReport().split("\n")) {
            sender.sendMessage(text(line, line.startsWith("=") ? AQUA : line.startsWith("-") ? GOLD : line.contains("Usage") && mem.getHeapUsageRatio() > 0.85 ? RED : WHITE));
        }
    }

    private void sendEntities(final CommandSender sender, final String[] args) {
        int limit = 15;
        if (args.length >= 2) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (final NumberFormatException ignored) {}
        }
        final var counter = io.papermc.paper.agc.profiling.AgcEntityCounter.get();
        final var top = counter.getTopEntities(limit);
        final var worlds = counter.getWorldBreakdown();

        sender.sendMessage(text("=== AGC Real-Time Entity Census ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(text("--- World Breakdown ---", YELLOW));
        for (final var w : worlds) {
            sender.sendMessage(text(String.format("  %-20s: %d entities (load: %.1f, chunks: %d, players: %d)",
                w.worldName(), w.totalEntities(), w.totalWeightedLoad(), w.loadedChunks(), w.playerCount()), WHITE));
        }
        sender.sendMessage(text("--- Top Entities by Weighted Tick Cost ---", YELLOW));
        for (final var e : top) {
            sender.sendMessage(text(String.format("  %-22s: count=%-6d (weight=%.1fx, load=%.1f)",
                e.type().name(), e.count(), e.weight(), e.totalWeightedLoad()), e.weight() >= 3.0 ? RED : e.weight() >= 1.5 ? YELLOW : WHITE));
        }
    }

    private void sendHotspots(final CommandSender sender, final String[] args) {
        int limit = 8;
        if (args.length >= 2) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (final NumberFormatException ignored) {}
        }
        final var counter = io.papermc.paper.agc.profiling.AgcEntityCounter.get();
        final var hotspots = counter.getTopHotspotChunks(limit);

        sender.sendMessage(text("=== AGC Dense Entity Chunk Hotspots ===", GOLD, TextDecoration.BOLD));
        if (hotspots.isEmpty()) {
            sender.sendMessage(text("No entity hotspots detected.", GRAY));
            return;
        }
        for (final var c : hotspots) {
            final StringBuilder types = new StringBuilder();
            c.topTypes().entrySet().stream()
                .sorted(java.util.Map.Entry.<org.bukkit.entity.EntityType, Integer>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> types.append(e.getKey().name()).append(":").append(e.getValue()).append(" "));

            sender.sendMessage(text(String.format("  [%s] Chunk [%d, %d]: ", c.worldName(), c.chunkX(), c.chunkZ()), AQUA)
                .append(text(c.totalEntities() + " entities", c.totalEntities() > 100 ? RED : YELLOW, TextDecoration.BOLD))
                .append(text(String.format(" (load: %.1f) - %s", c.weightedLoad(), types), GRAY)));
        }
    }

    private void handleGovernor(final CommandSender sender, final String[] args) {
        final var gov = io.papermc.paper.agc.AgcAdaptiveGovernor.get();
        if (args.length >= 3 && args[1].equalsIgnoreCase("override")) {
            final String target = args[2].toLowerCase(Locale.ROOT);
            if (target.equals("reset") || target.equals("auto")) {
                gov.setManualOverride(null);
                sender.sendMessage(text("Reset Adaptive Governor manual override. Autonomous governance resumed.", GREEN));
                return;
            }
            try {
                final int lvl = Integer.parseInt(target);
                if (lvl >= 0 && lvl < io.agcmc.agc.api.event.AgcPerformanceLevelEvent.PerformanceLevel.values().length) {
                    final var pLevel = io.agcmc.agc.api.event.AgcPerformanceLevelEvent.PerformanceLevel.values()[lvl];
                    gov.setManualOverride(pLevel);
                    sender.sendMessage(text("Set Adaptive Governor override to ", GREEN).append(text(pLevel.name(), GOLD, TextDecoration.BOLD)));
                    return;
                }
            } catch (final NumberFormatException ignored) {}
            sender.sendMessage(text("Invalid level '" + args[2] + "'. Use 0..4 or reset.", RED));
            return;
        }

        final var level = gov.getLevel();
        sender.sendMessage(text("=== AGC 4-Stage Adaptive Performance Governor ===", AQUA, TextDecoration.BOLD));
        sender.sendMessage(text("Current Level: ", WHITE)
            .append(text(level.name() + " (" + level.displayName() + ")", level == io.agcmc.agc.api.event.AgcPerformanceLevelEvent.PerformanceLevel.LEVEL_0_OPTIMAL ? GREEN : level.severity() <= 2 ? YELLOW : RED, TextDecoration.BOLD)));
        sender.sendMessage(text("Manual Override: ", WHITE)
            .append(text(gov.isManualOverrideActive() ? "ACTIVE (Manual Lock)" : "INACTIVE (Autonomous AI Governor)", gov.isManualOverrideActive() ? YELLOW : GREEN)));
        sender.sendMessage(text("EAR Multiplier: ", WHITE).append(text(String.format("%.0f%%", gov.getEntityActivationMultiplier() * 100.0), GOLD)));
        sender.sendMessage(text("Block Entity Tick Interval: ", WHITE).append(text(gov.getBlockEntityTickInterval() + " ticks", GOLD)));
        sender.sendMessage(text("Chunk Gen Allowed: ", WHITE).append(text(String.valueOf(gov.isChunkGenAllowed()), gov.isChunkGenAllowed() ? GREEN : RED)));
        sender.sendMessage(text("Mob Spawning Allowed: ", WHITE).append(text(String.valueOf(gov.isMobSpawningAllowed()), gov.isMobSpawningAllowed() ? GREEN : RED)));
        sender.sendMessage(text("Redstone Batching: ", WHITE).append(text(String.valueOf(gov.isRedstoneBatchingEnabled()), gov.isRedstoneBatchingEnabled() ? YELLOW : GRAY)));
        sender.sendMessage(text("Total Governor Transitions: ", WHITE).append(text(String.valueOf(gov.getTotalTransitions()), GOLD)));
        sender.sendMessage(text("Usage: /agc governor [override <0..4 | reset>]", GRAY));
    }

    private void sendStatus(final CommandSender sender) {
        final AgcCapabilityMatrix.Mode mode = AgcCapabilityMatrix.getMode();
        final AgcFoliaTuning.PoolStatus pool = AgcFoliaTuning.status();
        final AgcHotPathCache.CacheSnapshot mem = AgcHotPathCache.recordSnapshot();
        final AgcNetworkEnhancer.Metrics net = AgcNetworkEnhancer.get().metrics();
        final long usedBytes = mem.totalMemory() - mem.freeMemory();

        sender.sendMessage(text("=== AGC Performance Engine Status ===", AQUA, TextDecoration.BOLD));
        sender.sendMessage(text("Operating Mode: ", WHITE).append(text(mode.name(), mode == AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE ? GREEN : YELLOW, TextDecoration.BOLD)));
        sender.sendMessage(text("Async Workload Pool: ", WHITE)
            .append(text(pool.started() ? "RUNNING" : "STOPPED", pool.started() ? GREEN : RED))
            .append(text(String.format(" (%d core threads, %d active, %d queued tasks)", pool.coreSize(), pool.activeThreads(), pool.queueSize()), GRAY)));
        sender.sendMessage(text("Memory Usage: ", WHITE)
            .append(text(String.format("%.1f MB / %.1f MB (%.1f%% used, %.1f MB free)",
                usedBytes / (1024.0 * 1024.0),
                mem.maxMemory() / (1024.0 * 1024.0),
                mem.usedMemoryRatio() * 100.0,
                mem.freeMemory() / (1024.0 * 1024.0)),
                mem.usedMemoryRatio() > 0.85 ? RED : GREEN)));
        sender.sendMessage(text("Netty Pipeline: ", WHITE)
            .append(text(AgcNetworkEnhancer.get().isEnabled() ? "ACTIVE" : "DISABLED", AgcNetworkEnhancer.get().isEnabled() ? GREEN : RED))
            .append(text(String.format(" (%d channels tuned, %d active channels, %d errors)", net.applied(), net.liveChannels(), net.errors()), GRAY)));
        sender.sendMessage(text("Cache Metrics: ", WHITE).append(text(AgcMetrics.report().replace("\n", " | "), GRAY)));
    }

    private void handleMode(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage(text("Usage: /agc mode <VANILLA | AGC_BASELINE | AGC_AGGRESSIVE>", RED));
            return;
        }
        final String input = args[1].toUpperCase(Locale.ROOT);
        AgcCapabilityMatrix.Mode target = null;
        for (final AgcCapabilityMatrix.Mode m : AgcCapabilityMatrix.Mode.values()) {
            if (m.name().equals(input) || m.name().replace("AGC_", "").equals(input)) {
                target = m;
                break;
            }
        }
        if (target == null) {
            sender.sendMessage(text("Invalid mode '" + args[1] + "'. Available: VANILLA, AGC_BASELINE, AGC_AGGRESSIVE", RED));
            return;
        }
        AgcCapabilityMatrix.setMode(target);
        // AGC start - re-apply file config under the new mode, preserving operator pins
        io.papermc.paper.agc.AgcConfigSync.get().sync(true);
        // AGC end
        sender.sendMessage(text("AGC Operating Mode successfully switched to: ", GREEN)
            .append(text(target.name(), GOLD, TextDecoration.BOLD)));
    }

    private void sendReport(final CommandSender sender) {
        final List<String> reports = AgcFoliaTuning.budgetReport();
        sender.sendMessage(text("=== AGC Multi-World Tick Budgets ===", GOLD, TextDecoration.BOLD));
        if (reports.isEmpty()) {
            sender.sendMessage(text("No world tick metrics recorded yet.", GRAY));
            return;
        }
        for (final String line : reports) {
            sender.sendMessage(text("  * ", YELLOW).append(text(line, WHITE)));
        }
    }

    private void sendFeatures(final CommandSender sender) {
        final String fullReport = AgcCapabilityMatrix.report();
        sender.sendMessage(text("=== AGC Capability & Feature Matrix ===", AQUA, TextDecoration.BOLD));
        for (final String line : fullReport.split("\n")) {
            if (line.startsWith("  [")) {
                sender.sendMessage(text(line, GOLD, TextDecoration.BOLD));
            } else if (line.contains(":")) {
                final boolean active = line.contains("override") || !line.contains("(disabled)");
                sender.sendMessage(text(line, active ? GREEN : GRAY));
            } else {
                sender.sendMessage(text(line, WHITE));
            }
        }
    }

    private void handleOverride(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(text("Usage: /agc override <FEATURE_NAME> <true | false | reset>", RED));
            return;
        }
        final AgcCapabilityMatrix.Feature target = resolveFeature(args[1]);
        if (target == null) {
            sender.sendMessage(text("Unknown feature '" + args[1] + "'. Use tab-complete for options.", RED));
            return;
        }
        final String val = args[2].toLowerCase(Locale.ROOT);
        if (val.equals("reset")) {
            AgcCapabilityMatrix.setRuntimeOverride(target, null);
            io.papermc.paper.agc.AgcConfigSync.get().notifyOperatorOverride(target);
            sender.sendMessage(text("Reset override for ", GREEN).append(text(target.name(), YELLOW)).append(text(". Inheriting mode default.", GREEN)));
        } else if (val.equals("true") || val.equals("false")) {
            final boolean boolVal = Boolean.parseBoolean(val);
            AgcCapabilityMatrix.setRuntimeOverride(target, boolVal);
            io.papermc.paper.agc.AgcConfigSync.get().notifyOperatorOverride(target);
            sender.sendMessage(text("Set override for ", GREEN).append(text(target.name(), YELLOW)).append(text(" -> " + boolVal, GOLD, TextDecoration.BOLD)));
        } else {
            sender.sendMessage(text("Invalid override value '" + args[2] + "'. Use true, false, or reset.", RED));
        }
    }

    private void sendMaxOpt(final CommandSender sender) {
        sender.sendMessage(text("=== AGC Max-Optimization Batch (live) ===", AQUA, TextDecoration.BOLD));
        sender.sendMessage(text("mode=" + AgcCapabilityMatrix.getMode(), WHITE));
        final var matrix = AgcCapabilityMatrix.activeBySafety();
        int active = 0;
        for (final var list : matrix.values()) {
            active += list.size();
        }
        sender.sendMessage(text("matrix active features: " + active, GRAY));
        final var lith = io.papermc.paper.agc.entity.AgcLithiumCollisionEngine.get().metrics();
        sender.sendMessage(text("[LITHIUM collision] enabled=" + AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.LITHIUM_COLLISION_ENGINE)
            + " evals=" + lith.pushPairEvaluations() + " crammingEarlyTerms=" + lith.crammingEarlyTerminations()
            + " projSkips=" + lith.projectilePairSkips() + " fluidSkips=" + lith.fluidPushSkips()
            + " (NMS-wired; Entity.push pair-skip inlined uncounted by design)", GREEN));
        sender.sendMessage(text("[JIGSAW octree] enabled=" + AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.JIGSAW_BOX_OCTREE)
            + " fits=" + io.papermc.paper.agc.worldgen.AgcJigsawBoxOctree.globalFitsCalls()
            + " overlapHits=" + io.papermc.paper.agc.worldgen.AgcJigsawBoxOctree.globalOverlapHits() + " (NMS-wired)", GREEN));
        sender.sendMessage(text("[ENCODE cache] config=" + packetEncodingCacheFlag()
            + " wraps=" + net.minecraft.network.MeteusPreEncodedPacket.meteus$wraps()
            + " replays=" + net.minecraft.network.MeteusPreEncodedPacket.meteus$replays() + " (NMS-wired, opt-in)", GREEN));
        // AGC - registry-sync burst encoding replay: captures = encodes actually performed,
        // replays = per-join encodes skipped. Plugin-transparent (no packet wrapper is ever created).
        sender.sendMessage(text("[REGISTRY encode] enabled="
            + AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.REGISTRY_ENCODING_CACHE)
            + " captures=" + io.papermc.paper.agc.AgcStaticPacketEncodingCache.captures()
            + " replays=" + io.papermc.paper.agc.AgcStaticPacketEncodingCache.replays()
            + " firstRecipients=" + io.papermc.paper.agc.AgcStaticPacketEncodingCache.misses()
            + " held=" + io.papermc.paper.agc.AgcStaticPacketEncodingCache.size() + " (NMS-wired)", GREEN));
        hintVerify(sender);
        final var uni = io.papermc.paper.agc.network.AgcUniverseNetEngine.get().metrics();
        final var enh = AgcNetworkEnhancer.get().metrics();
        sender.sendMessage(text("[UNIVERSE net] enabled=" + AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.UNIVERSE_NET_ENGINE)
            + " adaptations=" + uni.compressionAdaptations() + " throttles=" + uni.trackerThrottles()
            + " batches=" + uni.broadcastBatches() + " channelsApplied=" + enh.applied()
            + " inboundPaced=" + uni.inboundChannelsPaced() + " loginsPaced=" + uni.loginsPaced()
            + " spawnsPaced=" + uni.spawnsPaced() + " spawnsDrained=" + uni.spawnsDrained()
            + " channelsLive=" + enh.liveChannels() + " errors=" + enh.errors(), WHITE));
        final var c2me = io.papermc.paper.agc.chunk.AgcC2meChunkPipeline.get().metrics();
        sender.sendMessage(text("[C2ME pipeline] enabled=" + AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.C2ME_CHUNK_PIPELINE)
            + " async=" + c2me.asyncSerializations() + " backpressureWaits=" + c2me.backpressureWaits()
            + " throttled=" + c2me.throttledTickets(), WHITE));
        final var bridge = io.papermc.paper.agc.tick.AgcRegionTickBridge.get().metrics();
        sender.sendMessage(text("[REGION bridge] enabled=" + AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.REGION_TICK_BRIDGE)
            + " helpers=" + bridge.helpersCompleted() + " commits=" + bridge.commitsDrained() + " pending=" + bridge.pendingCommits(), WHITE));
        final var light = io.papermc.paper.agc.light.AgcParallelLightEngine.get().metrics();
        sender.sendMessage(text("[LIGHT parallel] enabled=" + AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_LIGHT_ENGINE)
            + " batches=" + light.batchesDispatched() + " sections=" + light.sectionsProcessed(), WHITE));
        final var poi = io.papermc.paper.agc.entity.AgcPoiSearchEngine.get().metrics();
        sender.sendMessage(text("[POI search] enabled=" + AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.POI_SEARCH_ENGINE)
            + " queries=" + poi.queries() + " retrievals=" + poi.sectionRetrievals(), WHITE));
        final var dedup = io.papermc.paper.agc.worldgen.AgcTemplatePoolDedup.get().metrics();
        sender.sendMessage(text("[TEMPLATE dedup] enabled=" + AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.TEMPLATE_POOL_DEDUP)
            + " checks=" + dedup.candidateChecks() + " skips=" + dedup.duplicateSkips() + " selected=" + dedup.placementsSelected(), WHITE));
        final var lithium = io.papermc.paper.agc.entity.AgcLithiumCollisionEngine.get().metrics();
        sender.sendMessage(text("[LITHIUM collision] enabled=" + AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.LITHIUM_COLLISION_ENGINE)
            + " cramming=" + lithium.crammingEarlyTerminations() + " suffocation=" + lithium.suffocationFastPaths()
            + " projectileSkips=" + lithium.projectilePairSkips() + " pushSkips=" + lithium.unpushableSkips(), WHITE));
    }

    private static void hintVerify(final CommandSender sender) {
        sender.sendMessage(text("measure these claims for real: /agc verify <FEATURE>  (see /agc verify)", GRAY));
    }

    private static boolean packetEncodingCacheFlag() {
        try {
            return io.papermc.paper.configuration.GlobalConfiguration.get().agc.performance.packetEncodingCacheFastPath;
        } catch (final Throwable t) {
            return false;
        }
    }

    private static AgcCapabilityMatrix.Feature resolveFeature(final String raw) {
        if (raw == null) {
            return null;
        }
        final String name = raw.toUpperCase(Locale.ROOT);
        for (final AgcCapabilityMatrix.Feature feature : AgcCapabilityMatrix.Feature.values()) {
            if (feature.name().equals(name)) {
                return feature;
            }
        }
        return null;
    }

    private static int intArg(final String[] args, final int index, final int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * AGC — {@code /agc verify}: paired ON/OFF measurement of individual capability features on this live
     * server, plus an honest census of what is still an unproven claim.
     */
    private void handleVerify(final CommandSender sender, final String[] args) {
        final io.papermc.paper.agc.AgcFeatureScoper scoper = io.papermc.paper.agc.AgcFeatureScoper.get();
        final String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";

        switch (action) {
            case "cancel" -> {
                if (!scoper.isRunning() && scoper.queuedCount() == 0) {
                    sender.sendMessage(text("No scope run is active.", GRAY));
                    return;
                }
                scoper.requestCancel();
                sender.sendMessage(text("Scope run cancelled; the feature's override is being restored.", YELLOW));
            }
            case "status" -> {
                sender.sendMessage(text("state: " + scoper.status(), WHITE));
                sender.sendMessage(text("active=" + (scoper.activeFeature() == null ? "none" : scoper.activeFeature().name())
                    + " queued=" + scoper.queuedCount(), GRAY));
            }
            case "all" -> {
                final int pairs = intArg(args, 2, io.papermc.paper.agc.AgcFeatureScoper.DEFAULT_PAIRS);
                final int window = intArg(args, 3, io.papermc.paper.agc.AgcFeatureScoper.DEFAULT_WINDOW_TICKS);
                final java.util.ArrayList<AgcCapabilityMatrix.Feature> enabled = new java.util.ArrayList<>();
                for (final AgcCapabilityMatrix.Feature feature : AgcCapabilityMatrix.Feature.values()) {
                    if (AgcCapabilityMatrix.isEnabled(feature)) {
                        enabled.add(feature);
                    }
                }
                final String error = scoper.requestScopeAll(enabled, pairs, window);
                if (error != null) {
                    sender.sendMessage(text(error, RED));
                    return;
                }
                final int minutes = (io.papermc.paper.agc.AgcFeatureScoper.estimateSeconds(pairs, window) * enabled.size()) / 60;
                sender.sendMessage(text("Scoping " + enabled.size() + " currently-enabled feature(s), ~" + minutes
                    + " min total. Each feature is force-toggled while it is measured, then restored.", YELLOW));
                sendVerifyList(sender, scoper);
            }
            default -> {
                final AgcCapabilityMatrix.Feature feature = resolveFeature(args.length >= 2 ? args[1] : null);
                if (feature == null) {
                    if (!"list".equals(action)) {
                        sender.sendMessage(text("Unknown feature '" + args[1] + "'. Use tab-complete for options.", RED));
                    }
                    sendVerifyList(sender, scoper);
                    return;
                }
                final int pairs = intArg(args, 2, io.papermc.paper.agc.AgcFeatureScoper.DEFAULT_PAIRS);
                final int window = intArg(args, 3, io.papermc.paper.agc.AgcFeatureScoper.DEFAULT_WINDOW_TICKS);
                final String error = scoper.requestScope(feature, pairs, window);
                if (error != null) {
                    sender.sendMessage(text(error, RED));
                    return;
                }
                final io.papermc.paper.agc.AgcFeatureScoper.Result previous = scoper.result(feature);
                sender.sendMessage(text("Scoping " + feature.name() + " for ~"
                    + io.papermc.paper.agc.AgcFeatureScoper.estimateSeconds(pairs, window) + "s (ABBA x " + pairs
                    + ", " + window + " ticks per window).", GREEN));
                if (previous != null) {
                    sender.sendMessage(text("previous: " + previous.describe(), GRAY));
                }
                sender.sendMessage(text("the feature is toggled while it runs and restored afterwards;"
                    + " the server is measurably loaded or the verdict will be INCONCLUSIVE_IDLE.", GRAY));
            }
        }
    }

    private void sendVerifyList(final CommandSender sender, final io.papermc.paper.agc.AgcFeatureScoper scoper) {
        final var results = scoper.results();
        int helps = 0;
        int hurts = 0;
        int noise = 0;
        int inconclusive = 0;
        for (final var result : results) {
            switch (result.verdict()) {
                case HELPS -> helps++;
                case HURTS -> hurts++;
                case NOISE -> noise++;
                case INCONCLUSIVE_IDLE -> inconclusive++;
                default -> { }
            }
        }
        final java.util.ArrayList<String> unproven = new java.util.ArrayList<>();
        final java.util.Set<AgcCapabilityMatrix.Feature> dormant = AgcCapabilityMatrix.dormantFeatures();
        for (final AgcCapabilityMatrix.Feature feature : AgcCapabilityMatrix.Feature.values()) {
            if (dormant.contains(feature)) {
                continue; // dormant features claim nothing by default; they are audited, not scorable
            }
            if (AgcCapabilityMatrix.isEnabled(feature) && scoper.result(feature) == null) {
                unproven.add(feature.name());
            }
        }
        final int dormantCount = dormant.size();

        sender.sendMessage(text("=== AGC Feature Scoper - paired ON/OFF on this live server ===", AQUA, TextDecoration.BOLD));
        sender.sendMessage(text("state: " + scoper.status(), WHITE));
        sender.sendMessage(text("measured: helps=" + helps + " hurts=" + hurts + " noise=" + noise
            + " inconclusive=" + inconclusive + " | features=" + AgcCapabilityMatrix.Feature.values().length
            + " | dormant=" + dormantCount
            + " | enabled-but-unscoped=" + unproven.size(), GRAY));
        printVerdicts(sender, results, io.papermc.paper.agc.AgcFeatureScoper.Verdict.HURTS, RED,
            "HURTS - measurably slower with the feature on (consider turning it off)");
        printVerdicts(sender, results, io.papermc.paper.agc.AgcFeatureScoper.Verdict.INCONCLUSIVE_IDLE, YELLOW,
            "INCONCLUSIVE - server was too idle to measure; re-run under real load");
        printVerdicts(sender, results, io.papermc.paper.agc.AgcFeatureScoper.Verdict.NOISE, GRAY,
            "NOISE - indistinguishable from run-to-run spread at that load");
        printVerdicts(sender, results, io.papermc.paper.agc.AgcFeatureScoper.Verdict.HELPS, GREEN,
            "HELPS - measurably faster with the feature on");
        if (!unproven.isEmpty()) {
            final int shown = Math.min(8, unproven.size());
            sender.sendMessage(text("UNSCOPED (enabled, never proven here): "
                + String.join(", ", unproven.subList(0, shown)) + (unproven.size() > shown ? " ..." : ""), YELLOW));
        }
        sender.sendMessage(text("/agc verify <FEATURE> [pairs] [windowTicks] | all | status | cancel", GRAY));
    }

    private void printVerdicts(final CommandSender sender, final java.util.List<io.papermc.paper.agc.AgcFeatureScoper.Result> results,
                               final io.papermc.paper.agc.AgcFeatureScoper.Verdict verdict,
                               final net.kyori.adventure.text.format.NamedTextColor color, final String header) {
        boolean printed = false;
        for (final var result : results) {
            if (result.verdict() != verdict) {
                continue;
            }
            if (!printed) {
                sender.sendMessage(text("-- " + header + " --", color, TextDecoration.BOLD));
                printed = true;
            }
            sender.sendMessage(text("   " + result.describe(), color));
        }
    }

    private void runBenchmark(final CommandSender sender) {
        sender.sendMessage(text("[AGC Benchmark] Starting hot-path stress test (1,000,000 ops)...", YELLOW));
        final long start = System.nanoTime();

        // 1. TickBudget acquire throughput
        final AgcHotPathCache.TickBudget budget = new AgcHotPathCache.TickBudget();
        int budgetSuccess = 0;
        for (int i = 0; i < 1_000_000; i++) {
            if (budget.tryAcquire(500_000)) {
                budgetSuccess++;
            }
        }

        // 2. ChunkPacketCache throughput
        final AgcHotPathCache.ChunkPacketCache cache = new AgcHotPathCache.ChunkPacketCache(1024, 64 * 1024 * 1024);
        final byte[] dummyData = new byte[128];
        for (int i = 0; i < 50_000; i++) {
            cache.put(0, i, dummyData);
        }
        int hits = 0;
        for (int i = 0; i < 50_000; i++) {
            if (cache.get(0, i) != null) {
                hits++;
            }
        }

        final long elapsedNanos = System.nanoTime() - start;
        final double elapsedMs = elapsedNanos / 1_000_000.0;
        final double opsPerSec = (1_100_000.0 / (elapsedNanos / 1_000_000_000.0));

        sender.sendMessage(text("[AGC Benchmark] Completed in ", GREEN)
            .append(text(String.format("%.2fms", elapsedMs), GOLD, TextDecoration.BOLD))
            .append(text(String.format(" (%,.0f ops/sec, Cache entries: %d, Hits: %d)", opsPerSec, cache.size(), hits), WHITE)));
    }
}
