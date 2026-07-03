package net.minecraft.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central compatibility envelope for every AGC optimisation.
 * <p>
 * The rule is intentionally simple: optimisations may be aggressive, but they
 * must never become authoritative gameplay semantics. When a signal says an
 * optimisation would affect vanilla/Paper feel, plugin callback order, packet
 * latency, or chunk state ordering, the envelope keeps the visible operation in
 * its deterministic inline/order-preserving form while allowing safe read-only
 * preparation and accounting to continue.
 */
public final class AGCOptimizationEnvelope {
    public static final AGCOptimizationEnvelope INSTANCE = new AGCOptimizationEnvelope();

    public enum Area {
        NETWORK,
        CHUNK_SEND,
        CHUNK_LOAD,
        CHUNK_GENERATION,
        ENTITY_TRACKING,
        PLAYER_FANOUT,
        MULTI_WORLD_TICK,
        THREAD_TRANSLATION,
        MINIGAME_API
    }

    public enum Outcome {
        AGGRESSIVE_ALLOWED,
        COMPAT_ALLOWED,
        SEMANTIC_PRESERVED_INLINE,
        DISABLED_BY_ROLLBACK
    }

    private final EnumMap<Area, AtomicLong> allowedCounts = new EnumMap<>(Area.class);
    private final EnumMap<Area, AtomicLong> compatCounts = new EnumMap<>(Area.class);
    private final EnumMap<Area, AtomicLong> semanticInlineCounts = new EnumMap<>(Area.class);
    private final EnumMap<Area, AtomicLong> rollbackDeniedCounts = new EnumMap<>(Area.class);
    private final EnumMap<Area, AtomicLong> lastJournalNanos = new EnumMap<>(Area.class);

    private volatile boolean enabled = true;
    private volatile boolean preservePluginCompatibility = true;
    private volatile boolean preserveVanillaFeel = true;
    private volatile int maxInteractiveDelayTicks = 1;
    private volatile boolean strictUnknownPluginParallelGate = true;
    private volatile double lastMspt;
    private volatile long lastPendingFlushes;
    private volatile long lastTranslatorPending;
    private volatile AGCScalingController.Profile lastProfile = AGCScalingController.Profile.BASELINE;

    private AGCOptimizationEnvelope() {
        for (final Area area : Area.values()) {
            this.allowedCounts.put(area, new AtomicLong());
            this.compatCounts.put(area, new AtomicLong());
            this.semanticInlineCounts.put(area, new AtomicLong());
            this.rollbackDeniedCounts.put(area, new AtomicLong());
            this.lastJournalNanos.put(area, new AtomicLong());
        }
    }

    public void configure(
        final boolean enabled,
        final boolean preservePluginCompatibility,
        final boolean preserveVanillaFeel,
        final int maxInteractiveDelayTicks,
        final boolean strictUnknownPluginParallelGate
    ) {
        this.enabled = enabled;
        this.preservePluginCompatibility = preservePluginCompatibility;
        this.preserveVanillaFeel = preserveVanillaFeel;
        this.maxInteractiveDelayTicks = Math.max(0, maxInteractiveDelayTicks);
        this.strictUnknownPluginParallelGate = strictUnknownPluginParallelGate;
    }

    public void recordRuntimeSample(
        final double mspt,
        final long pendingFlushes,
        final long translatorPending,
        final AGCScalingController.Profile profile
    ) {
        this.lastMspt = Math.max(0.0D, mspt);
        this.lastPendingFlushes = Math.max(0L, pendingFlushes);
        this.lastTranslatorPending = Math.max(0L, translatorPending);
        this.lastProfile = profile == null ? AGCScalingController.Profile.BASELINE : profile;
    }

    public Outcome admitNetworkCoalescing(final String packetName, final long pendingFlushes) {
        if (!this.enabled) {
            return this.record(Area.NETWORK, Outcome.COMPAT_ALLOWED, "envelope disabled");
        }
        if (!AGCPerformanceGovernor.INSTANCE.isFeatureAllowed(AGCPerformanceGovernor.Feature.PACKET_BUDGETING)) {
            return this.record(Area.NETWORK, Outcome.DISABLED_BY_ROLLBACK, "packet budgeting rolled back");
        }
        if (this.preserveVanillaFeel && this.maxInteractiveDelayTicks <= 0) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.NETWORK, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, "interactive delay budget is zero");
            return this.record(Area.NETWORK, Outcome.SEMANTIC_PRESERVED_INLINE, "interactive delay budget is zero");
        }
        if (this.preserveVanillaFeel && (this.lastProfile == AGCScalingController.Profile.EMERGENCY_SEMANTIC_GUARD || pendingFlushes > 65_536L)) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.NETWORK, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, "deadline pressure for " + packetName);
            return this.record(Area.NETWORK, Outcome.SEMANTIC_PRESERVED_INLINE, "deadline pressure for " + packetName);
        }
        return this.record(Area.NETWORK, Outcome.AGGRESSIVE_ALLOWED, "network coalescing admitted for " + packetName);
    }

    public Outcome admitChunkOperation(final Area area, final String reason) {
        final Area normalized = switch (area) {
            case CHUNK_GENERATION -> Area.CHUNK_GENERATION;
            case CHUNK_LOAD -> Area.CHUNK_LOAD;
            default -> Area.CHUNK_SEND;
        };
        if (!this.enabled) {
            return this.record(normalized, Outcome.COMPAT_ALLOWED, "envelope disabled");
        }
        if (!AGCPerformanceGovernor.INSTANCE.isFeatureAllowed(AGCPerformanceGovernor.Feature.CHUNK_QUEUE_BUDGETING)) {
            return this.record(normalized, Outcome.DISABLED_BY_ROLLBACK, "chunk budgeting rolled back");
        }
        if (this.preserveVanillaFeel && this.lastProfile == AGCScalingController.Profile.EMERGENCY_SEMANTIC_GUARD) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.CHUNK, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, "emergency profile keeps chunk order");
            return this.record(normalized, Outcome.SEMANTIC_PRESERVED_INLINE, "emergency profile keeps chunk order");
        }
        return this.record(normalized, Outcome.AGGRESSIVE_ALLOWED, reason);
    }

    public Outcome admitParallelWorldTick(final boolean hasBlockedPlugin, final boolean hasUnknownOrWarnPlugin, final int worldCount) {
        if (!this.enabled) {
            return this.record(Area.MULTI_WORLD_TICK, Outcome.COMPAT_ALLOWED, "envelope disabled");
        }
        if (!AGCPerformanceGovernor.INSTANCE.isFeatureAllowed(AGCPerformanceGovernor.Feature.PARALLEL_WORLD_TICK)) {
            return this.record(Area.MULTI_WORLD_TICK, Outcome.DISABLED_BY_ROLLBACK, "parallel world tick rolled back");
        }
        if (!AGCThreadTranslator.INSTANCE.isEnabled() || AGCThreadTranslator.INSTANCE.isBacklogged()) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.PLUGIN, AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT, "translator unavailable/backlogged");
            return this.record(Area.MULTI_WORLD_TICK, Outcome.SEMANTIC_PRESERVED_INLINE, "translator unavailable/backlogged");
        }
        if (this.preservePluginCompatibility && hasBlockedPlugin) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.PLUGIN, AGCSemanticInvariant.Decision.CONFLICT_SERIALISED, "blocked plugin keeps ordered world commit");
            return this.record(Area.MULTI_WORLD_TICK, Outcome.SEMANTIC_PRESERVED_INLINE, "blocked plugin keeps ordered world commit");
        }
        if (this.preservePluginCompatibility && this.strictUnknownPluginParallelGate && hasUnknownOrWarnPlugin) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.PLUGIN, AGCSemanticInvariant.Decision.CONFLICT_SERIALISED, "unknown/warn plugin keeps ordered world commit");
            return this.record(Area.MULTI_WORLD_TICK, Outcome.SEMANTIC_PRESERVED_INLINE, "unknown/warn plugin keeps ordered world commit");
        }
        if (this.preserveVanillaFeel && worldCount < 2) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.WORLD_TICK, AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT, "not enough worlds for parallel waves");
            return this.record(Area.MULTI_WORLD_TICK, Outcome.SEMANTIC_PRESERVED_INLINE, "not enough worlds for parallel waves");
        }
        if (this.preserveVanillaFeel && this.lastProfile == AGCScalingController.Profile.EMERGENCY_SEMANTIC_GUARD) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.WORLD_TICK, AGCSemanticInvariant.Decision.CONFLICT_SERIALISED, "emergency profile keeps ordered commit");
            return this.record(Area.MULTI_WORLD_TICK, Outcome.SEMANTIC_PRESERVED_INLINE, "emergency profile keeps ordered commit");
        }
        return this.record(Area.MULTI_WORLD_TICK, Outcome.AGGRESSIVE_ALLOWED, "parallel world tick admitted worlds=" + worldCount);
    }

    public Outcome admitEntityTrackingFastPath(final String reason) {
        if (!this.enabled) {
            return this.record(Area.ENTITY_TRACKING, Outcome.COMPAT_ALLOWED, "envelope disabled");
        }
        if (!AGCPerformanceGovernor.INSTANCE.isFeatureAllowed(AGCPerformanceGovernor.Feature.SPATIAL_GRID_FAST_PATH)) {
            return this.record(Area.ENTITY_TRACKING, Outcome.DISABLED_BY_ROLLBACK, "entity fast path rolled back");
        }
        if (this.preserveVanillaFeel && this.lastMspt > 0.0D && this.lastMspt >= 150.0D) {
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.ENTITY, AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED, "extreme MSPT keeps tracker order");
            return this.record(Area.ENTITY_TRACKING, Outcome.SEMANTIC_PRESERVED_INLINE, "extreme MSPT keeps tracker order");
        }
        return this.record(Area.ENTITY_TRACKING, Outcome.AGGRESSIVE_ALLOWED, reason);
    }

    public boolean isAggressive(final Outcome outcome) {
        return outcome == Outcome.AGGRESSIVE_ALLOWED || outcome == Outcome.COMPAT_ALLOWED;
    }

    private Outcome record(final Area area, final Outcome outcome, final String reason) {
        switch (outcome) {
            case AGGRESSIVE_ALLOWED -> this.allowedCounts.get(area).incrementAndGet();
            case COMPAT_ALLOWED -> this.compatCounts.get(area).incrementAndGet();
            case SEMANTIC_PRESERVED_INLINE -> {
                this.semanticInlineCounts.get(area).incrementAndGet();
                this.journal(area, outcome, reason);
            }
            case DISABLED_BY_ROLLBACK -> {
                this.rollbackDeniedCounts.get(area).incrementAndGet();
                this.journal(area, outcome, reason);
            }
        }
        return outcome;
    }

    private void journal(final Area area, final Outcome outcome, final String reason) {
        final long now = System.nanoTime();
        final AtomicLong slot = this.lastJournalNanos.get(area);
        final long previous = slot.get();
        if (now - previous < java.util.concurrent.TimeUnit.SECONDS.toNanos(5L)) {
            return;
        }
        if (slot.compareAndSet(previous, now)) {
            AGCStabilityJournal.INSTANCE.record(
                "envelope",
                area + " -> " + outcome + ": " + (reason == null || reason.isBlank() ? "unspecified" : reason)
            );
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.preservePluginCompatibility,
            this.preserveVanillaFeel,
            this.maxInteractiveDelayTicks,
            this.strictUnknownPluginParallelGate,
            this.lastMspt,
            this.lastPendingFlushes,
            this.lastTranslatorPending,
            this.lastProfile,
            copy(this.allowedCounts),
            copy(this.compatCounts),
            copy(this.semanticInlineCounts),
            copy(this.rollbackDeniedCounts)
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCOptimizationEnvelope{enabled=" + snapshot.enabled()
            + ", preservePluginCompatibility=" + snapshot.preservePluginCompatibility()
            + ", preserveVanillaFeel=" + snapshot.preserveVanillaFeel()
            + ", maxInteractiveDelayTicks=" + snapshot.maxInteractiveDelayTicks()
            + ", strictUnknownPluginParallelGate=" + snapshot.strictUnknownPluginParallelGate()
            + ", profile=" + snapshot.profile()
            + ", mspt=" + snapshot.mspt()
            + ", pendingFlushes=" + snapshot.pendingFlushes()
            + ", translatorPending=" + snapshot.translatorPending()
            + ", allowed=" + snapshot.allowed()
            + ", compat=" + snapshot.compatAllowed()
            + ", semanticInline=" + snapshot.semanticInline()
            + ", rollbackDenied=" + snapshot.rollbackDenied()
            + '}';
    }

    private static Map<Area, Long> copy(final EnumMap<Area, AtomicLong> source) {
        final EnumMap<Area, Long> copy = new EnumMap<>(Area.class);
        for (final Map.Entry<Area, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(copy);
    }

    public record Snapshot(
        boolean enabled,
        boolean preservePluginCompatibility,
        boolean preserveVanillaFeel,
        int maxInteractiveDelayTicks,
        boolean strictUnknownPluginParallelGate,
        double mspt,
        long pendingFlushes,
        long translatorPending,
        AGCScalingController.Profile profile,
        Map<Area, Long> allowed,
        Map<Area, Long> compatAllowed,
        Map<Area, Long> semanticInline,
        Map<Area, Long> rollbackDenied
    ) {
    }
}
