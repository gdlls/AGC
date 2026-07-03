package net.minecraft.server;

import java.util.concurrent.TimeUnit;

/**
 * End-of-tick health integration for AGC opt-in features.
 * <p>
 * This records broad server health, drains deferred low-priority network flushes
 * so batching cannot become visible stutter, and drains deterministic ordered
 * commits. Alpha8 prefers budget tightening and semantic-preserving ordered
 * commits over disabling whole optimisations.
 */
public final class AGCRuntimeHealth {
    public static final AGCRuntimeHealth INSTANCE = new AGCRuntimeHealth();

    private volatile long lastTickTimeNanos;
    private volatile double lastMspt;
    private volatile int lastDeferredFlushDrains;
    private volatile int lastTranslatorDrains;
    private volatile int lastDeterministicCommits;
    private volatile int lastPluginFailures;
    private volatile long previousTranslatorFailedTasks;
    private volatile long previousChunkThrottles;
    private volatile SurvivalMetrics cachedSurvivalMetrics = new SurvivalMetrics(0, 0, 0, 0);
    private volatile long nextHeavySurvivalSampleTick;
    private volatile long heavySurvivalSamples;

    private AGCRuntimeHealth() {
    }

    public void recordServerTick(final long tickTimeNanos) {
        final long agcTickSequence = AGCSemanticInvariant.INSTANCE.nextTickSequence();
        this.lastTickTimeNanos = Math.max(0L, tickTimeNanos);
        this.lastMspt = this.lastTickTimeNanos / (double) TimeUnit.MILLISECONDS.toNanos(1L);
        AGCRuntimeConfigurator.applyGlobalConfigurationSnapshot();
        AGCSemanticInvariant.INSTANCE.beginTick(agcTickSequence);

        final AGCThreadTranslator.Snapshot translatorBeforeDrain = AGCThreadTranslator.INSTANCE.snapshot();
        final AGCPerformanceStandard.Pressure pressure = AGCPerformanceStandard.INSTANCE.sample(
            this.lastTickTimeNanos,
            translatorBeforeDrain.pendingTasks() + AGCDeterministicTaskPipeline.INSTANCE.pendingCommits(),
            AGCNetworkAdmission.INSTANCE.pendingDeferredFlushConnections(),
            AGCDeterministicTaskPipeline.INSTANCE.pendingCommits()
        );
        AGCTickBudgetArbiter.INSTANCE.beginTick(agcTickSequence, pressure);
        final SurvivalMetrics survivalMetrics = this.sampleSurvivalMetrics(agcTickSequence);
        AGCSurvivalScaleCoordinator.INSTANCE.beginTick(
            agcTickSequence,
            survivalMetrics.players(),
            survivalMetrics.worlds(),
            survivalMetrics.loadedChunks(),
            survivalMetrics.entities(),
            this.lastMspt
        );
        AGCScaleKernel.INSTANCE.beginTick(
            agcTickSequence,
            survivalMetrics.players(),
            survivalMetrics.worlds(),
            survivalMetrics.loadedChunks(),
            survivalMetrics.entities(),
            this.lastMspt
        );
        AGCScaleControlPlane.INSTANCE.beginTick(
            agcTickSequence,
            survivalMetrics.players(),
            survivalMetrics.worlds(),
            survivalMetrics.loadedChunks(),
            survivalMetrics.entities(),
            this.lastMspt
        );
        AGCScale16ControlLaw.INSTANCE.beginTick(
            agcTickSequence,
            survivalMetrics.players(),
            survivalMetrics.worlds(),
            survivalMetrics.loadedChunks(),
            survivalMetrics.entities(),
            this.lastMspt
        );
        AGCPluginBackpressureBridge.INSTANCE.beginTick(agcTickSequence, translatorBeforeDrain.pendingTasks());
        AGCHotspotEvictionPlanner.INSTANCE.beginTick(agcTickSequence);
        AGCHotspotEvictionPlanner.INSTANCE.record(agcTickSequence, survivalMetrics.players());
        AGCRegionHotspotMap.INSTANCE.beginTick(agcTickSequence);
        AGCComputeTopologyPlanner.INSTANCE.beginTick(agcTickSequence, survivalMetrics.players(), this.lastMspt);
        AGCScale20LosslessPipeline.INSTANCE.recordMspt(this.lastMspt);
        AGCNoInvasionOptimizer.INSTANCE.beginTick(agcTickSequence);
        AGCEntitySpatialSnapshotCache.INSTANCE.beginTick(agcTickSequence);
        AGCSharedReadOnlyCache.INSTANCE.beginTick(agcTickSequence);
        AGCMinigameBurstPlanner.INSTANCE.beginTick(agcTickSequence);

        final AGCScalingController.Profile profile = AGCScalingController.INSTANCE.sampleAndApply(
            this.lastMspt,
            AGCNetworkAdmission.INSTANCE.pendingDeferredFlushConnections(),
            translatorBeforeDrain.pendingTasks()
        );
        AGCOptimizationEnvelope.INSTANCE.recordRuntimeSample(
            this.lastMspt,
            AGCNetworkAdmission.INSTANCE.pendingDeferredFlushConnections(),
            translatorBeforeDrain.pendingTasks(),
            profile
        );
        AGCEntityAdmission.INSTANCE.refillTickBudgets();

        this.lastDeferredFlushDrains = AGCNetworkAdmission.INSTANCE.drainDeferredFlushes();
        for (int i = 0; i < this.lastDeferredFlushDrains; ++i) {
            AGCLatencySLO.INSTANCE.recordNetworkDrain();
        }
        this.lastDeterministicCommits = AGCDeterministicTaskPipeline.INSTANCE.drainOrderedCommits();
        for (int i = 0; i < this.lastDeterministicCommits; ++i) {
            AGCLatencySLO.INSTANCE.recordOrderedCommitDrain(0);
        }
        this.lastTranslatorDrains = AGCThreadTranslator.INSTANCE.drainOnPrimaryThread();

        final AGCThreadTranslator.Snapshot translatorAfterDrain = AGCThreadTranslator.INSTANCE.snapshot();
        final long failedNow = translatorAfterDrain.failedTasks();
        this.lastPluginFailures = saturatedInt(Math.max(0L, failedNow - this.previousTranslatorFailedTasks));
        this.previousTranslatorFailedTasks = failedNow;

        final AGCChunkAdmission.Snapshot chunkSnapshot = AGCChunkAdmission.INSTANCE.snapshot();
        final long chunkThrottlesNow = chunkSnapshot.throttledLoads() + chunkSnapshot.throttledGenerates() + chunkSnapshot.deferredFlushChunkSends();
        final int chunkPressure = saturatedInt(Math.max(0L, chunkThrottlesNow - this.previousChunkThrottles));
        this.previousChunkThrottles = chunkThrottlesNow;

        final int queueDepth = saturatedInt(
            AGCNetworkAdmission.INSTANCE.pendingDeferredFlushConnections()
                + translatorAfterDrain.pendingTasks()
        );
        final AGCPerformanceGovernor.Decision decision = AGCPerformanceGovernor.INSTANCE.recordHealthSample(
            this.lastMspt,
            this.lastPluginFailures,
            chunkPressure,
            queueDepth
        );
        if (decision == AGCPerformanceGovernor.Decision.THROTTLE || decision == AGCPerformanceGovernor.Decision.DISABLE_UNTIL_RESTART) {
            AGCSemanticInvariant.INSTANCE.record(
                AGCSemanticInvariant.Domain.WORLD_TICK,
                AGCSemanticInvariant.Decision.CONFLICT_SERIALISED,
                "runtime threshold tightens semantic budgets"
            );
            if (this.lastPluginFailures > 0 || AGCThreadTranslator.INSTANCE.isBacklogged()) {
                AGCPerformanceGovernor.INSTANCE.disableFeatureUntilRestart(
                    AGCPerformanceGovernor.Feature.PARALLEL_WORLD_TICK,
                    "plugin-sensitive translated task failure/backlog"
                );
            }
            AGCStabilityJournal.INSTANCE.record(
                "governor",
                "runtime threshold handled with semantic-preserving budget tightening: mspt=" + this.lastMspt
                    + " queueDepth=" + queueDepth
                    + " chunkPressure=" + chunkPressure
                    + " pluginFailures=" + this.lastPluginFailures
                    + " survivalMode=" + AGCSurvivalScaleCoordinator.INSTANCE.mode()
            );
        }
    }

    private SurvivalMetrics sampleSurvivalMetrics(final long tickSequence) {
        int players = this.cachedSurvivalMetrics.players();
        int worlds = this.cachedSurvivalMetrics.worlds();
        try {
            players = org.bukkit.Bukkit.getOnlinePlayers().size();
            worlds = org.bukkit.Bukkit.getWorlds().size();
        } catch (final Throwable ignored) {
            return this.cachedSurvivalMetrics;
        }

        final SurvivalMetrics cached = this.cachedSurvivalMetrics;
        if (tickSequence < this.nextHeavySurvivalSampleTick) {
            return new SurvivalMetrics(players, worlds, cached.loadedChunks(), cached.entities());
        }

        int loadedChunks = 0;
        int entities = 0;
        try {
            for (final org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                try {
                    loadedChunks += world.getLoadedChunks().length;
                } catch (final Throwable ignored) {
                    loadedChunks += Math.max(0, cached.loadedChunks() / Math.max(1, cached.worlds()));
                }
                try {
                    entities += world.getEntities().size();
                } catch (final Throwable ignored) {
                    entities += Math.max(0, cached.entities() / Math.max(1, cached.worlds()));
                }
            }
        } catch (final Throwable ignored) {
            return new SurvivalMetrics(players, worlds, cached.loadedChunks(), cached.entities());
        }

        final SurvivalMetrics sampled = new SurvivalMetrics(players, worlds, loadedChunks, entities);
        this.cachedSurvivalMetrics = sampled;
        this.heavySurvivalSamples++;
        final long interval = this.lastMspt >= 45.0D ? 40L : players >= 512 ? 10L : 20L;
        this.nextHeavySurvivalSampleTick = tickSequence + interval;
        return sampled;
    }

    public Snapshot snapshot() {
        return new Snapshot(this.lastTickTimeNanos, this.lastMspt, this.lastDeferredFlushDrains, this.lastTranslatorDrains, this.lastDeterministicCommits, this.lastPluginFailures);
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCRuntimeHealth{lastMspt=" + snapshot.lastMspt()
            + ", lastTickTimeNanos=" + snapshot.lastTickTimeNanos()
            + ", deferredFlushDrains=" + snapshot.deferredFlushDrains()
            + ", translatorDrains=" + snapshot.translatorDrains()
            + ", deterministicCommits=" + snapshot.deterministicCommits()
            + ", pluginFailures=" + snapshot.pluginFailures()
            + ", survivalMetrics=" + this.cachedSurvivalMetrics
            + ", heavySurvivalSamples=" + this.heavySurvivalSamples
            + '}';
    }

    private static int saturatedInt(final long value) {
        if (value <= 0L) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    public record Snapshot(long lastTickTimeNanos, double lastMspt, int deferredFlushDrains, int translatorDrains, int deterministicCommits, int pluginFailures) {
    }

    private record SurvivalMetrics(int players, int worlds, int loadedChunks, int entities) {
    }
}
