package net.minecraft.server;

import io.papermc.paper.configuration.GlobalConfiguration;

/** Applies AGC config values to lightweight runtime scaffolds. */
public final class AGCRuntimeConfigurator {
    private static final long CONFIG_APPLY_INTERVAL_NANOS = 1_000_000_000L;
    private static volatile GlobalConfiguration lastAppliedConfig;
    private static volatile long nextAllowedApplyNanos;

    private AGCRuntimeConfigurator() {
    }

    public static void applyGlobalConfigurationSnapshot() {
        final GlobalConfiguration config = GlobalConfiguration.get();
        if (config == null || config.agc == null || config.agc.performance == null) {
            return;
        }
        final long now = System.nanoTime();
        if (config == lastAppliedConfig && now < nextAllowedApplyNanos) {
            return;
        }
        lastAppliedConfig = config;
        nextAllowedApplyNanos = now + CONFIG_APPLY_INTERVAL_NANOS;
        AGCThreadTranslator.INSTANCE.setEnabled(config.agc.performance.pluginThreadTranslationLayer);
        AGCThreadTranslator.INSTANCE.setMaxDrainPerTick(config.agc.performance.pluginThreadTranslationMaxDrainPerTick);
        AGCPacketBudget.INSTANCE.setEnabled(config.agc.performance.packetPriorityBudgeting);
        AGCPacketBudget.INSTANCE.configure(config.agc.performance.packetBudgetBytesPerSecond, config.agc.performance.packetBudgetBurstBytes);
        AGCChunkBudget.INSTANCE.setEnabled(config.agc.performance.chunkQueueBudgeting);
        AGCChunkBudget.INSTANCE.configure(
            config.agc.performance.chunkBudgetSendsPerSecond,
            config.agc.performance.chunkBudgetLoadsPerSecond,
            config.agc.performance.chunkBudgetGeneratesPerSecond
        );
        AGCPerformanceGovernor.INSTANCE.setAutomaticRollbackEnabled(config.agc.performance.automaticRollbackGovernor);
        AGCNetworkAdmission.INSTANCE.configureDeferredFlushLimits(
            config.agc.performance.networkDeferredFlushMaxPending,
            config.agc.performance.networkDeferredFlushDrainPerTick
        );
        AGCScalingController.INSTANCE.configure(
            config.agc.performance.adaptiveScalingProfiles,
            config.agc.performance.adaptiveScalingHighDensityPlayers,
            config.agc.performance.adaptiveScalingEmergencyMspt
        );
        AGCPerformanceGovernor.INSTANCE.configure(
            config.agc.performance.rollbackMsptThreshold,
            config.agc.performance.rollbackQueueDepthThreshold,
            3
        );
        AGCOptimizationEnvelope.INSTANCE.configure(
            config.agc.performance.optimizationEnvelopeEnabled,
            config.agc.performance.optimizationPreservePluginCompatibility,
            config.agc.performance.optimizationPreserveVanillaFeel,
            config.agc.performance.optimizationMaxInteractiveDelayTicks,
            config.agc.performance.optimizationStrictUnknownPluginParallelGate
        );
        AGCNoInvasionOptimizer.INSTANCE.configure(
            config.agc.performance.noInvasionOptimizer,
            config.agc.performance.noInvasionStrictSemantics,
            config.agc.performance.optimizationMaxInteractiveDelayTicks,
            config.agc.performance.networkDeferredFlushMaxPending,
            config.agc.performance.noInvasionMaxTranslatorPending
        );
        AGCChunkFairQueue.INSTANCE.configure(
            config.agc.performance.chunkBudgetSendsPerSecond,
            config.agc.performance.chunkBudgetLoadsPerSecond,
            config.agc.performance.chunkBudgetGeneratesPerSecond,
            config.agc.performance.chunkFairQueueMaxCarryMultiplier
        );
        AGCSemanticInvariant.INSTANCE.configure(config.agc.performance.semanticInvariantLedger);
        AGCEntityAdmission.INSTANCE.configure(config.agc.performance.entityTrackerFastPathBudgetPerTick);
        AGCEntitySnapshotPlanner.INSTANCE.configure(config.agc.performance.entityTrackerFastPathBudgetPerTick);
        AGCDeterministicTaskPipeline.INSTANCE.configure(
            config.agc.performance.deterministicTaskPipeline,
            config.agc.performance.deterministicTaskPipelineMaxCommitsPerTick
        );
        AGCWorldConcurrencyPlanner.INSTANCE.configure(
            config.agc.performance.parallelWorldTick,
            config.agc.performance.worldPlannerPrimaryThreadPluginCallbacks,
            config.agc.performance.parallelWorldTickMinWorlds
        );
        AGCPlayerIntentScheduler.INSTANCE.configure(
            config.agc.performance.playerIntentScheduler,
            config.agc.performance.playerIntentNetworkCosmeticBudget,
            config.agc.performance.playerIntentChunkBudget,
            config.agc.performance.entityTrackerFastPathBudgetPerTick,
            config.agc.performance.playerIntentArenaBudget
        );
        AGCShardPlanner.INSTANCE.configure(
            config.agc.performance.shardPlanner,
            config.agc.performance.shardPlayersPerShard,
            config.agc.performance.shardMaxShards
        );
        AGCPluginCompatibilityContracts.INSTANCE.configure(config.agc.performance.pluginCompatibilityContracts);
        AGCPerformanceStandard.INSTANCE.configure(
            true,
            config.agc.performance.performanceTargetMspt,
            config.agc.performance.performanceHardMspt,
            config.agc.performance.optimizationMaxInteractiveDelayTicks,
            config.agc.performance.latencySloMaxOrderedCommitTicks,
            config.agc.performance.performanceMaxSemanticQueueDepth
        );
        AGCTickBudgetArbiter.INSTANCE.configure(
            config.agc.performance.tickBudgetArbiter,
            config.agc.performance.tickBudgetUnitsPerTick,
            config.agc.performance.tickBudgetMinUnitsPerCategory,
            config.agc.performance.tickBudgetHardPressureDivisor
        );
        AGCLatencySLO.INSTANCE.configure(
            config.agc.performance.latencySloAccounting,
            config.agc.performance.optimizationMaxInteractiveDelayTicks,
            config.agc.performance.latencySloMaxOrderedCommitTicks
        );
        AGCEntitySpatialSnapshotCache.INSTANCE.configure(
            config.agc.performance.entitySpatialSnapshotCache,
            config.agc.performance.entitySpatialSnapshotMaxEntries
        );
        AGCMinigameBurstPlanner.INSTANCE.configure(
            config.agc.performance.minigameBurstPlanner,
            config.agc.performance.minigameArenaPrewarmBudgetPerTick,
            config.agc.performance.minigameArenaCommitBudgetPerTick
        );
        AGCSurvivalScaleCoordinator.INSTANCE.configure(
            config.agc.performance.survivalScaleCoordinator,
            config.agc.performance.survivalScaleTargetPlayers,
            config.agc.performance.survivalScaleTargetTps,
            config.agc.performance.survivalScaleHotspotPlayersPerCell
        );
        AGCResourceEfficiencyEngine.INSTANCE.configure(
            config.agc.performance.resourceEfficiencyEngine,
            config.agc.performance.survivalScaleTargetPlayers,
            config.agc.performance.survivalScaleTargetTps,
            config.agc.performance.resourceEfficiencyReserveCpuPercent,
            config.agc.performance.resourceEfficiencyMaxMemoryPressureBytes,
            config.agc.performance.resourceEfficiencyBaseUnitsPerCore,
            config.agc.performance.resourceEfficiencyMaxCarryMultiplier
        );
        AGCInterestGraph.INSTANCE.configure(
            config.agc.performance.interestGraph,
            config.agc.performance.interestGraphCellSizeBlocks,
            config.agc.performance.interestGraphTargetPlayersPerCell,
            config.agc.performance.interestGraphMaxCells
        );
        AGCGlobalFairnessMatrix.INSTANCE.configure(
            config.agc.performance.globalFairnessMatrix,
            config.agc.performance.globalFairnessPlayerQuantum,
            config.agc.performance.globalFairnessWorldQuantum,
            config.agc.performance.globalFairnessArenaQuantum,
            config.agc.performance.globalFairnessRegionQuantum,
            config.agc.performance.globalFairnessMaxCarryMultiplier
        );
        AGCSharedReadOnlyCache.INSTANCE.configure(
            config.agc.performance.sharedReadOnlyCache,
            config.agc.performance.sharedReadOnlyCacheMaxEntries
        );
        AGCComputeTopologyPlanner.INSTANCE.configure(true, 2, 22, 96_000L);
        AGCFanoutDeduplicator.INSTANCE.configure(true, 524_288);
        AGCChunkIntentPlanner.INSTANCE.configure(true, 262_144);
        AGCEntityVisibilityPreselector.INSTANCE.configure(true, 4096);
        AGCStorageIoGovernor.INSTANCE.configure(true, 32_768L);
        AGCScaleKernel.INSTANCE.configure(
            config.agc.performance.scaleKernel,
            config.agc.performance.survivalScaleTargetPlayers,
            config.agc.performance.survivalScaleTargetTps,
            config.agc.performance.scaleKernelReserveCpuPercent,
            config.agc.performance.scaleKernelUnitsPerCore,
            config.agc.performance.scaleKernelMaxWorkers
        );
        AGCRegionHotspotMap.INSTANCE.configure(
            config.agc.performance.regionHotspotMap,
            config.agc.performance.regionHotspotCellSizeBlocks,
            config.agc.performance.regionHotspotPlayers,
            config.agc.performance.regionHotspotMaxCells
        );
        AGCNetworkFanoutKernel.INSTANCE.configure(
            config.agc.performance.networkFanoutKernel,
            config.agc.performance.networkFanoutKernelMaxShapes,
            config.agc.performance.optimizationMaxInteractiveDelayTicks
        );
        AGCChunkPipelineKernel.INSTANCE.configure(
            config.agc.performance.chunkPipelineKernel,
            config.agc.performance.chunkPipelinePerPlayerQuantum
        );
        AGCEntityTrackerKernel.INSTANCE.configure(
            config.agc.performance.entityTrackerKernel,
            config.agc.performance.entityTrackerKernelPerPlayerQuantum
        );
        AGCThreadAffinityTranslator.INSTANCE.configure(config.agc.performance.threadAffinityTranslator);
        AGCPluginLogicTranslator.INSTANCE.configure(config.agc.performance.pluginLogicTranslator);
        AGCScaleControlPlane.INSTANCE.configure(
            config.agc.performance.scaleControlPlane,
            config.agc.performance.survivalScaleTargetPlayers,
            config.agc.performance.survivalScaleTargetTps,
            config.agc.performance.scaleControlReserveCpuPercent,
            config.agc.performance.scaleControlUnitsPerCore
        );
        AGCPacketShapeTable.INSTANCE.configure(
            config.agc.performance.packetShapeTable,
            config.agc.performance.packetShapeTableMaxShapes,
            config.agc.performance.packetShapeTableMaxFanout
        );
        AGCPlayerCohortTable.INSTANCE.configure(
            config.agc.performance.playerCohortTable,
            config.agc.performance.playerCohortCellSizeBlocks,
            config.agc.performance.playerCohortTargetPlayers,
            config.agc.performance.playerCohortMaxCohorts
        );
        AGCChunkIntentCompactor.INSTANCE.configure(
            config.agc.performance.chunkIntentCompactor,
            config.agc.performance.chunkIntentCompactorRegionSizeChunks,
            config.agc.performance.chunkIntentCompactorMaxShapes
        );
        AGCEntityBandingPlanner.INSTANCE.configure(
            config.agc.performance.entityBandingPlanner,
            config.agc.performance.entityBandingMaxCandidatesPerPlayer
        );
        AGCMemoryLocalityPlanner.INSTANCE.configure(
            config.agc.performance.memoryLocalityPlanner,
            config.agc.performance.memoryLocalityMaxScratchBytesPerTick,
            config.agc.performance.memoryLocalityMaxOwners
        );
        AGCEncodingReusePlanner.INSTANCE.configure(
            config.agc.performance.encodingReusePlanner,
            config.agc.performance.encodingReuseMaxShapes,
            config.agc.performance.encodingReuseCompressionThresholdBytes
        );
        AGCPluginBackpressureBridge.INSTANCE.configure(
            config.agc.performance.pluginBackpressureBridge,
            config.agc.performance.pluginBackpressureMaxPendingTasks
        );
        AGCHotspotEvictionPlanner.INSTANCE.configure(
            config.agc.performance.hotspotEvictionPlanner,
            config.agc.performance.hotspotEvictionMaxHotspots,
            config.agc.performance.hotspotEvictionPlayers
        );
        AGCScale16ControlLaw.INSTANCE.configure(true, config.agc.performance.survivalScaleTargetTps, 720_000L);
        AGCPlayerMotionIntentModel.INSTANCE.configure(3, config.agc.performance.playerCohortCellSizeBlocks);
        AGCChunkLookaheadPlanner.INSTANCE.configure(14, 384);
        AGCEntitySpatialBandIndex.INSTANCE.configure(config.agc.performance.entityBandingMaxCandidatesPerPlayer);
        AGCNetworkDeltaShapePlanner.INSTANCE.configure(config.agc.performance.packetShapeTableMaxShapes);
        AGCScale17AlgorithmKernel.INSTANCE.configure(true, 820_000L, config.agc.performance.survivalScaleTargetPlayers, 28);
        AGCChunkPredictiveIndex.INSTANCE.configure(16, 512);
        AGCEntityInteractionGraph.INSTANCE.configure(config.agc.performance.entityBandingMaxCandidatesPerPlayer * 2, 64);
        AGCNetworkRecipientCohortGraph.INSTANCE.configure(64, 8192);
        AGCWorldPhaseExecutorPlan.INSTANCE.configure(96);
        AGCPluginContractVerifier.INSTANCE.configure(true);
        AGCCacheLocalityWindow.INSTANCE.configure(160L * 1024L * 1024L);
    }
}
