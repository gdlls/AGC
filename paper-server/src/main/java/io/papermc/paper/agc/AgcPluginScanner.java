package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AGC — Dynamic Plugin Concurrency & Compatibility Scanner.
 *
 * <p>Scans installed plugins to detect their concurrency profiles and cross-world mutation
 * tendencies, allowing AGC to safely enable full parallel world execution for isolated worlds
 * while enforcing deterministic sequential synchronization for legacy or cross-world plugins.</p>
 */
public final class AgcPluginScanner {

    private static final AgcPluginScanner INSTANCE = new AgcPluginScanner();

    public enum ConcurrencyProfile {
        /** Isolated/read-only: 100% parallel safe across all worlds */
        FULLY_PARALLEL_SAFE,
        /** Cross-world mutator: requires post-barrier cross-world queueing */
        CROSS_WORLD_MUTATING,
        /** Legacy plugin: requires primary-thread event delivery */
        LEGACY_SYNC_SENSITIVE
    }

    private final Map<String, PluginReport> scannedPlugins = new ConcurrentHashMap<>();

    public static AgcPluginScanner get() {
        return INSTANCE;
    }

    private AgcPluginScanner() {}

    /**
     * Registers a plugin's concurrency profile analysis.
     *
     * @param pluginName Name of the plugin
     * @param profile    Detected {@link ConcurrencyProfile}
     * @param details    Analysis description / detected listeners
     */
    public void registerPluginAnalysis(
        final String pluginName,
        final ConcurrencyProfile profile,
        final String details
    ) {
        if (pluginName == null) {
            return;
        }
        this.scannedPlugins.put(
            pluginName.toLowerCase(Locale.ROOT),
            new PluginReport(pluginName, profile != null ? profile : ConcurrencyProfile.FULLY_PARALLEL_SAFE, details != null ? details : "")
        );
    }

    /**
     * Resolves the profile for a given plugin name (defaults to FULLY_PARALLEL_SAFE if unlisted).
     */
    public ConcurrencyProfile getProfile(final String pluginName) {
        if (pluginName == null) {
            return ConcurrencyProfile.FULLY_PARALLEL_SAFE;
        }
        final PluginReport report = this.scannedPlugins.get(pluginName.toLowerCase(Locale.ROOT));
        return report != null ? report.profile() : ConcurrencyProfile.FULLY_PARALLEL_SAFE;
    }

    /**
     * Checks whether any scanned plugin requires strict cross-world serialization.
     */
    public boolean hasCrossWorldMutatingPlugins() {
        for (final PluginReport report : this.scannedPlugins.values()) {
            if (report.profile() == ConcurrencyProfile.CROSS_WORLD_MUTATING) {
                return true;
            }
        }
        return false;
    }

    public List<PluginReport> getAllReports() {
        return Collections.unmodifiableList(new ArrayList<>(this.scannedPlugins.values()));
    }

    public void clear() {
        this.scannedPlugins.clear();
    }

    public record PluginReport(
        String pluginName,
        ConcurrencyProfile profile,
        String details
    ) {
    }
}
