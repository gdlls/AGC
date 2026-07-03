package net.minecraft.server;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Compatibility guard for AGC semantic-preserving features.
 * <p>
 * AGC's public contract is still Paper/Bukkit plugin compatibility. This bridge
 * scans known plugin categories and feeds the world concurrency planner so AGC
 * can optimise read-only preparation and independent waves without moving
 * plugin-facing commits out of deterministic order.
 */
public final class AGCCompatibilityBridge {
    public enum Risk {
        SAFE,
        WARN,
        BLOCKED
    }

    private static final Set<String> PACKET_PIPELINE_PLUGINS = lowerSet(
        "ProtocolLib", "ViaVersion", "ViaBackwards", "ViaRewind", "Geyser-Spigot", "floodgate"
    );
    private static final Set<String> CROSS_WORLD_OR_ENTITY_PLUGINS = lowerSet(
        "Multiverse-Core", "Multiverse", "Citizens", "CoreProtect", "WorldEdit", "FastAsyncWorldEdit", "FAWE"
    );
    private static final Set<String> PERMISSION_OR_CHAT_SAFE_PLUGINS = lowerSet(
        "LuckPerms", "Vault", "Essentials", "EssentialsX", "PlaceholderAPI"
    );
    private static final long PLUGIN_SCAN_CACHE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5L);
    private static volatile long lastPluginScanNanos;
    private static volatile List<PluginFinding> cachedPluginFindings = Collections.emptyList();
    private static volatile List<String> cachedPluginNames = Collections.emptyList();

    private AGCCompatibilityBridge() {}

    public static boolean isMainThreadCompatibleMode() {
        return !shouldEnableExperimentalParallelWorldTick();
    }

    /**
     * Gate for semantic world-wave ticking. The raw config opts in; this method
     * asks the write-intent planner whether at least one conflict-free wave can
     * run without moving plugin-visible commits out of deterministic order.
     */
    public static boolean shouldEnableExperimentalParallelWorldTick() {
        return shouldEnableExperimentalParallelWorldTick(null);
    }

    public static boolean shouldEnableExperimentalParallelWorldTick(final ServerLevel[] levels) {
        AGCRuntimeConfigurator.applyGlobalConfigurationSnapshot();
        final GlobalConfiguration config = GlobalConfiguration.get();
        if (config == null || config.agc == null || config.agc.performance == null) {
            return false;
        }
        if (!config.agc.performance.parallelWorldTick) {
            return false;
        }
        if (!AGCPerformanceGovernor.INSTANCE.isFeatureAllowed(AGCPerformanceGovernor.Feature.PARALLEL_WORLD_TICK)) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.WORLD_TICK, AGCSemanticInvariant.Decision.ROLLBACK_DENIED, "parallel feature disabled by governor");
            return false;
        }
        if (AGCThreadTranslator.INSTANCE.isBacklogged() || !AGCThreadTranslator.INSTANCE.isEnabled()) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.PLUGIN, AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT, "translator unavailable/backlogged");
            return false;
        }
        boolean hasBlockedPlugin = false;
        boolean hasUnknownOrWarnPlugin = false;
        for (final PluginFinding finding : scanInstalledPlugins()) {
            if (finding.risk() == Risk.BLOCKED) {
                hasBlockedPlugin = true;
            } else if (finding.risk() == Risk.WARN) {
                hasUnknownOrWarnPlugin = true;
            }
        }
        final int worldCount = levels == null ? 2 : levels.length;
        AGCOptimizationEnvelope.INSTANCE.admitParallelWorldTick(
            config.agc.performance.parallelWorldTickForceUnsafe ? false : hasBlockedPlugin,
            config.agc.performance.parallelWorldTickForceUnsafe ? false : hasUnknownOrWarnPlugin,
            worldCount
        );
        if (levels == null) {
            return true;
        }
        final AGCWorldConcurrencyPlanner.Plan plan = AGCWorldConcurrencyPlanner.INSTANCE.buildPlan(levels);
        return plan.parallelGroups() > 0;
    }

    public static List<String> installedPluginNames() {
        refreshPluginScanIfNeeded();
        return cachedPluginNames;
    }

    public static List<PluginFinding> scanInstalledPlugins() {
        refreshPluginScanIfNeeded();
        return cachedPluginFindings;
    }

    public static void invalidatePluginScan() {
        lastPluginScanNanos = 0L;
    }

    private static void refreshPluginScanIfNeeded() {
        final long now = System.nanoTime();
        if (now - lastPluginScanNanos <= PLUGIN_SCAN_CACHE_NANOS) {
            return;
        }
        synchronized (AGCCompatibilityBridge.class) {
            final long lockedNow = System.nanoTime();
            if (lockedNow - lastPluginScanNanos <= PLUGIN_SCAN_CACHE_NANOS) {
                return;
            }
            final Plugin[] plugins = installedPlugins();
            if (plugins.length == 0) {
                cachedPluginNames = Collections.emptyList();
                cachedPluginFindings = Collections.emptyList();
                lastPluginScanNanos = lockedNow;
                return;
            }
            final ArrayList<String> names = new ArrayList<>(plugins.length);
            final ArrayList<PluginFinding> findings = new ArrayList<>(plugins.length);
            for (final Plugin plugin : plugins) {
                final String name = plugin.getName();
                names.add(name);
                final String key = normalise(name);
                if (CROSS_WORLD_OR_ENTITY_PLUGINS.contains(key)) {
                    findings.add(new PluginFinding(name, "touches entities, blocks or multiple worlds during Bukkit events", Risk.BLOCKED));
                } else if (PACKET_PIPELINE_PLUGINS.contains(key)) {
                    findings.add(new PluginFinding(name, "hooks packet/network pipeline; keep packet ordering on the primary thread", Risk.WARN));
                } else if (PERMISSION_OR_CHAT_SAFE_PLUGINS.contains(key)) {
                    findings.add(new PluginFinding(name, "known low-risk service/chat/permission plugin", Risk.SAFE));
                } else {
                    findings.add(new PluginFinding(name, "unknown plugin; keep deterministic main-thread commits unless explicitly forced", Risk.WARN));
                }
            }
            cachedPluginNames = Collections.unmodifiableList(names);
            cachedPluginFindings = Collections.unmodifiableList(findings);
            lastPluginScanNanos = lockedNow;
        }
    }

    public static String compatibilityReport() {
        AGCRuntimeConfigurator.applyGlobalConfigurationSnapshot();
        final StringBuilder builder = new StringBuilder(256);
        builder.append("AGC compatibility mode: Bukkit-visible semantics ")
            .append(isMainThreadCompatibleMode() ? "deterministic ordered commits" : "full parallel world waves enabled")
            .append("; plugins=")
            .append(installedPluginNames());
        final List<PluginFinding> findings = scanInstalledPlugins();
        if (!findings.isEmpty()) {
            builder.append("; findings=").append(findings);
        }
        builder.append("; translator=").append(AGCThreadTranslator.INSTANCE.statusLine());
        builder.append("; governor=").append(AGCPerformanceGovernor.INSTANCE.statusLine());
        builder.append("; planner=").append(AGCWorldConcurrencyPlanner.INSTANCE.statusLine());
        builder.append("; semantics=").append(AGCSemanticInvariant.INSTANCE.statusLine());
        return builder.toString();
    }

    private static Plugin[] installedPlugins() {
        try {
            if (Bukkit.getPluginManager() == null) {
                return new Plugin[0];
            }
            return Bukkit.getPluginManager().getPlugins();
        } catch (final Throwable ignored) {
            return new Plugin[0];
        }
    }

    private static Set<String> lowerSet(final String... values) {
        final HashSet<String> set = new HashSet<>(values.length);
        Arrays.stream(values).map(AGCCompatibilityBridge::normalise).forEach(set::add);
        return Collections.unmodifiableSet(set);
    }

    private static String normalise(final String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    public record PluginFinding(String pluginName, String reason, Risk risk) {
    }
}
