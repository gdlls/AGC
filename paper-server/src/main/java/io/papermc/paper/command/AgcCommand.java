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
        "status", "mode", "report", "features", "override", "benchmark", "journal", "governor", "dashboard", "help"
    );

    public AgcCommand(final String name) {
        super(name);
        this.description = "AGC High-Performance Engine control & telemetry";
        this.usageMessage = "/agc [status | mode <mode> | report | features | override <feat> <true|false|reset> | benchmark]";
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
            case "features" -> sendFeatures(sender);
            case "override" -> handleOverride(sender, args);
            case "benchmark" -> runBenchmark(sender);
            case "journal" -> sendJournal(sender, args);
            case "governor" -> sendGovernor(sender);
            case "dashboard" -> sendDashboard(sender);
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
        sender.sendMessage(text("/agc mode <mode> ", YELLOW).append(text("- Switch mode (VANILLA, AGC_BASELINE, AGC_AGGRESSIVE)", GRAY)));
        sender.sendMessage(text("/agc report ", YELLOW).append(text("- Per-world tick latency and load budget report", GRAY)));
        sender.sendMessage(text("/agc features ", YELLOW).append(text("- List all performance features and safety tags", GRAY)));
        sender.sendMessage(text("/agc override <feature> <val> ", YELLOW).append(text("- Override feature state (true/false/reset)", GRAY)));
        sender.sendMessage(text("/agc benchmark ", YELLOW).append(text("- Run real-time micro-benchmark across hot-path caches", GRAY)));
        sender.sendMessage(text("/agc journal [limit] ", YELLOW).append(text("- View recent stability and governor event log", GRAY)));
        sender.sendMessage(text("/agc governor ", YELLOW).append(text("- View adaptive performance governor state & metrics", GRAY)));
        sender.sendMessage(text("/agc dashboard ", YELLOW).append(text("- Render complete real-time performance dashboard", GRAY)));
        sender.sendMessage(text("------------------------------------------------------------", GOLD));
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

    private void sendGovernor(final CommandSender sender) {
        final var gov = io.papermc.paper.agc.AgcPerformanceGovernor.get();
        final var m = gov.metrics();
        sender.sendMessage(text("=== AGC Performance Governor ===", AQUA, TextDecoration.BOLD));
        sender.sendMessage(text("Active State: ", WHITE).append(text(m.currentState().name(), m.currentState() == io.papermc.paper.agc.AgcPerformanceGovernor.State.HEALTHY ? GREEN : YELLOW, TextDecoration.BOLD)));
        sender.sendMessage(text("Total State Transitions: ", WHITE).append(text(String.valueOf(m.totalTransitions()), GOLD)));
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
        final String featName = args[1].toUpperCase(Locale.ROOT);
        AgcCapabilityMatrix.Feature target = null;
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            if (f.name().equals(featName)) {
                target = f;
                break;
            }
        }
        if (target == null) {
            sender.sendMessage(text("Unknown feature '" + args[1] + "'. Use tab-complete for options.", RED));
            return;
        }
        final String val = args[2].toLowerCase(Locale.ROOT);
        if (val.equals("reset")) {
            AgcCapabilityMatrix.setRuntimeOverride(target, null);
            sender.sendMessage(text("Reset override for ", GREEN).append(text(target.name(), YELLOW)).append(text(". Inheriting mode default.", GREEN)));
        } else if (val.equals("true") || val.equals("false")) {
            final boolean boolVal = Boolean.parseBoolean(val);
            AgcCapabilityMatrix.setRuntimeOverride(target, boolVal);
            sender.sendMessage(text("Set override for ", GREEN).append(text(target.name(), YELLOW)).append(text(" -> " + boolVal, GOLD, TextDecoration.BOLD)));
        } else {
            sender.sendMessage(text("Invalid override value '" + args[2] + "'. Use true, false, or reset.", RED));
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
