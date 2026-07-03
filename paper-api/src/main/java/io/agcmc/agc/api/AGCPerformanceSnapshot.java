package io.agcmc.agc.api;

import java.util.EnumMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Immutable performance/profile summary for plugin dashboards. */
public final class AGCPerformanceSnapshot {
    private final String profile;
    private final double mspt;
    private final int onlinePlayers;
    private final long pendingNetworkFlushes;
    private final long pendingTranslatedTasks;
    private final EnumMap<AGCFeature, AGCFeatureStatus> featureStatuses;

    public AGCPerformanceSnapshot(
        final @NotNull String profile,
        final double mspt,
        final int onlinePlayers,
        final long pendingNetworkFlushes,
        final long pendingTranslatedTasks,
        final @Nullable Map<AGCFeature, AGCFeatureStatus> featureStatuses
    ) {
        this.profile = profile;
        this.mspt = Math.max(0.0D, mspt);
        this.onlinePlayers = Math.max(0, onlinePlayers);
        this.pendingNetworkFlushes = Math.max(0L, pendingNetworkFlushes);
        this.pendingTranslatedTasks = Math.max(0L, pendingTranslatedTasks);
        this.featureStatuses = new EnumMap<>(AGCFeature.class);
        if (featureStatuses != null) {
            this.featureStatuses.putAll(featureStatuses);
        }
    }

    public @NotNull String profile() {
        return this.profile;
    }

    public double mspt() {
        return this.mspt;
    }

    public int onlinePlayers() {
        return this.onlinePlayers;
    }

    public long pendingNetworkFlushes() {
        return this.pendingNetworkFlushes;
    }

    public long pendingTranslatedTasks() {
        return this.pendingTranslatedTasks;
    }

    public @NotNull AGCFeatureStatus featureStatus(final @NotNull AGCFeature feature) {
        return this.featureStatuses.getOrDefault(feature, AGCFeatureStatus.UNKNOWN);
    }

    public @NotNull Map<AGCFeature, AGCFeatureStatus> featureStatuses() {
        return Map.copyOf(this.featureStatuses);
    }
}
