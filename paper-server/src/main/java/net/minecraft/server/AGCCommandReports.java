package net.minecraft.server;

/**
 * Text report helpers for the future /agc command surface.
 * <p>
 * Kept independent from Brigadier registration so it compiles as a lightweight
 * diagnostic utility now and can be wired into commands later without changing
 * the compatibility contract.
 */
public final class AGCCommandReports {
    private AGCCommandReports() {
    }

    public static String perf() {
        return String.join("\n",
            AGCRuntimeHealth.INSTANCE.statusLine(),
            AGCPerformanceGovernor.INSTANCE.statusLine(),
            AGCScalingController.INSTANCE.statusLine(),
            AGCPacketBudget.INSTANCE.statusLine(),
            AGCNetworkAdmission.INSTANCE.statusLine(),
            AGCChunkBudget.INSTANCE.statusLine(),
            AGCChunkAdmission.INSTANCE.statusLine(),
            AGCThreadTranslator.INSTANCE.statusLine(),
            AGCOptimizationEnvelope.INSTANCE.statusLine(),
            AGCPerformanceStandard.INSTANCE.statusLine(),
            AGCTickBudgetArbiter.INSTANCE.statusLine(),
            AGCLatencySLO.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            AGCSemanticInvariant.INSTANCE.statusLine(),
            AGCWorldConcurrencyPlanner.INSTANCE.statusLine(),
            AGCWorldWriteIntentGraph.INSTANCE.statusLine(),
            AGCDeterministicTaskPipeline.INSTANCE.statusLine(),
            AGCChunkFairQueue.INSTANCE.statusLine(),
            AGCEntityAdmission.INSTANCE.statusLine(),
            AGCPlayerIntentScheduler.INSTANCE.statusLine(),
            AGCShardPlanner.INSTANCE.statusLine(),
            AGCPluginCompatibilityContracts.INSTANCE.statusLine(),
            AGCEntitySnapshotPlanner.INSTANCE.statusLine(),
            AGCEntitySpatialSnapshotCache.INSTANCE.statusLine(),
            AGCMinigameBurstPlanner.INSTANCE.statusLine(),
            AGCSurvivalScaleCoordinator.INSTANCE.statusLine(),
            AGCResourceEfficiencyEngine.INSTANCE.statusLine(),
            AGCInterestGraph.INSTANCE.statusLine(),
            AGCGlobalFairnessMatrix.INSTANCE.statusLine(),
            AGCSharedReadOnlyCache.INSTANCE.statusLine(),
            AGCPlayerIntentScheduler.INSTANCE.statusLine(),
            AGCShardPlanner.INSTANCE.statusLine(),
            AGCPluginCompatibilityContracts.INSTANCE.statusLine(),
            AGCSurvivalScaleCoordinator.INSTANCE.statusLine(),
            AGCResourceEfficiencyEngine.INSTANCE.statusLine(),
            AGCInterestGraph.INSTANCE.statusLine(),
            AGCGlobalFairnessMatrix.INSTANCE.statusLine(),
            AGCSharedReadOnlyCache.INSTANCE.statusLine(),
            AGCComputeTopologyPlanner.INSTANCE.statusLine(),
            AGCFanoutDeduplicator.INSTANCE.statusLine(),
            AGCChunkIntentPlanner.INSTANCE.statusLine(),
            AGCEntityVisibilityPreselector.INSTANCE.statusLine(),
            AGCStorageIoGovernor.INSTANCE.statusLine(),
            AGCScaleKernel.INSTANCE.statusLine(),
            AGCRegionHotspotMap.INSTANCE.statusLine(),
            AGCNetworkFanoutKernel.INSTANCE.statusLine(),
            AGCChunkPipelineKernel.INSTANCE.statusLine(),
            AGCEntityTrackerKernel.INSTANCE.statusLine(),
            AGCThreadAffinityTranslator.INSTANCE.statusLine(),
            AGCPluginLogicTranslator.INSTANCE.statusLine(),
            AGCScale16ControlLaw.INSTANCE.statusLine(),
            AGCPlayerMotionIntentModel.INSTANCE.statusLine(),
            AGCChunkLookaheadPlanner.INSTANCE.statusLine(),
            AGCEntitySpatialBandIndex.INSTANCE.statusLine(),
            AGCNetworkDeltaShapePlanner.INSTANCE.statusLine(),
            AGCWorldLogicPhaseDAG.INSTANCE.statusLine(),
            AGCPluginSemanticModel.INSTANCE.statusLine(),
            AGCScale17AlgorithmKernel.INSTANCE.statusLine(),
            AGCChunkPredictiveIndex.INSTANCE.statusLine(),
            AGCEntityInteractionGraph.INSTANCE.statusLine(),
            AGCNetworkRecipientCohortGraph.INSTANCE.statusLine(),
            AGCWorldPhaseExecutorPlan.INSTANCE.statusLine(),
            AGCPluginContractVerifier.INSTANCE.statusLine(),
            AGCCacheLocalityWindow.INSTANCE.statusLine(),
            AGCScale18ControlPlane.INSTANCE.statusLine(),
            AGCUnifiedTickPlanCompiler.INSTANCE.statusLine(),
            AGCNetworkSendGraphCompiler.INSTANCE.statusLine(),
            AGCChunkSpatialRoutePlanner.INSTANCE.statusLine(),
            AGCEntityTrackerDeltaIndex.INSTANCE.statusLine(),
            AGCReadOnlyWorkStealingPlanner.INSTANCE.statusLine(),
            AGCPluginSemanticFirewall.INSTANCE.statusLine(),
            AGCScale19LogicKernel.INSTANCE.statusLine(),
            AGCNetworkMulticastShapeGraph.INSTANCE.statusLine(),
            AGCChunkDemandForecastTable.INSTANCE.statusLine(),
            AGCEntityInterestSetReducer.INSTANCE.statusLine(),
            AGCWorldPhaseBatchCompiler.INSTANCE.statusLine(),
            AGCPluginSemanticJit.INSTANCE.statusLine(),
            AGCAdaptiveLocalityRing.INSTANCE.statusLine(),
            AGCScale20LosslessPipeline.INSTANCE.statusLine(),
            AGCNetworkBackboneGraph.INSTANCE.statusLine(),
            AGCChunkWorksetSlicer.INSTANCE.statusLine(),
            AGCEntityWitnessIndex.INSTANCE.statusLine(),
            AGCWorldSemanticScheduler.INSTANCE.statusLine(),
            AGCPluginDeterminismGuard.INSTANCE.statusLine(),
            AGCResourcePlacementPlanner.INSTANCE.statusLine(),
            AGCScale21CriticalPathRuntime.INSTANCE.statusLine(),
            AGCPlayerClusterMovementPlanner.INSTANCE.statusLine(),
            AGCChunkStormController.INSTANCE.statusLine(),
            AGCEntityDensityField.INSTANCE.statusLine(),
            AGCNetworkBroadcastPlanner.INSTANCE.statusLine(),
            AGCPluginCommitSequencer.INSTANCE.statusLine(),
            AGCMultiverseLaneAllocator.INSTANCE.statusLine(),
            AGCComputeWasteReducer.INSTANCE.statusLine(),
            AGCStabilityJournal.INSTANCE.statusLine(),
            MeteusSpatialGrid.INSTANCE.generateMetricsReport()
        );
    }

    public static String compat() {
        return AGCCompatibilityBridge.compatibilityReport();
    }

    public static String rollbackProfile() {
        return "AGC rollback profile: " + AGCPerformanceGovernor.INSTANCE.rollbackCountsSnapshot();
    }

    public static String profile() {
        return AGCScalingController.INSTANCE.statusLine();
    }

    public static String journal() {
        return AGCStabilityJournal.INSTANCE.dump(20);
    }

    public static String safety() {
        return String.join("\n",
            "AGC safety envelope:",
            AGCOptimizationEnvelope.INSTANCE.statusLine(),
            AGCPerformanceStandard.INSTANCE.statusLine(),
            AGCTickBudgetArbiter.INSTANCE.statusLine(),
            AGCLatencySLO.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            AGCSemanticInvariant.INSTANCE.statusLine(),
            AGCWorldConcurrencyPlanner.INSTANCE.statusLine(),
            AGCWorldWriteIntentGraph.INSTANCE.statusLine(),
            AGCDeterministicTaskPipeline.INSTANCE.statusLine(),
            AGCCompatibilityBridge.compatibilityReport(),
            AGCRuntimeHealth.INSTANCE.statusLine(),
            AGCPerformanceGovernor.INSTANCE.statusLine(),
            AGCPerformanceStandard.INSTANCE.statusLine(),
            AGCTickBudgetArbiter.INSTANCE.statusLine(),
            AGCLatencySLO.INSTANCE.statusLine(),
            AGCEntityAdmission.INSTANCE.statusLine(),
            AGCPlayerIntentScheduler.INSTANCE.statusLine(),
            AGCShardPlanner.INSTANCE.statusLine(),
            AGCPluginCompatibilityContracts.INSTANCE.statusLine()
        );
    }

    public static String noInvasion() {
        return String.join("\n",
            "AGC no-invasion optimiser:",
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            AGCWorldWriteIntentGraph.INSTANCE.statusLine(),
            AGCChunkFairQueue.INSTANCE.statusLine(),
            AGCEntitySnapshotPlanner.INSTANCE.statusLine(),
            AGCEntitySpatialSnapshotCache.INSTANCE.statusLine(),
            AGCPlayerIntentScheduler.INSTANCE.statusLine(),
            AGCShardPlanner.INSTANCE.statusLine(),
            AGCNetworkAdmission.INSTANCE.statusLine(),
            AGCSemanticInvariant.INSTANCE.statusLine()
        );
    }


    public static String scale() {
        final AGCShardPlanner.Plan plan = AGCShardPlanner.INSTANCE.plan(2000, 128, 512);
        return String.join("\n",
            "AGC scale planner:",
            AGCShardPlanner.INSTANCE.statusLine(),
            AGCPlayerIntentScheduler.INSTANCE.statusLine(),
            "samplePlan players=" + plan.expectedPlayers()
                + " worlds=" + plan.activeWorlds()
                + " arenas=" + plan.activeArenas()
                + " recommendedShards=" + plan.recommendedShards(),
            AGCChunkFairQueue.INSTANCE.statusLine(),
            AGCEntitySnapshotPlanner.INSTANCE.statusLine()
        );
    }

    public static String contracts() {
        return String.join("\n",
            "AGC compatibility contracts:",
            AGCPluginCompatibilityContracts.INSTANCE.statusLine(),
            "apiDeclarations=" + io.agcmc.agc.api.AGC.compatibility().declarationCount(),
            "contracts are conservative hints: they never permit off-thread Bukkit mutation or event-order changes"
        );
    }

    public static String hardPerformance() {
        return String.join("\n",
            "AGC aggressive performance standard:",
            AGCPerformanceStandard.INSTANCE.statusLine(),
            AGCTickBudgetArbiter.INSTANCE.statusLine(),
            AGCLatencySLO.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            AGCNetworkAdmission.INSTANCE.statusLine(),
            AGCChunkAdmission.INSTANCE.statusLine(),
            AGCEntitySnapshotPlanner.INSTANCE.statusLine(),
            AGCEntitySpatialSnapshotCache.INSTANCE.statusLine(),
            AGCMinigameBurstPlanner.INSTANCE.statusLine(),
            AGCSurvivalScaleCoordinator.INSTANCE.statusLine(),
            AGCResourceEfficiencyEngine.INSTANCE.statusLine(),
            AGCInterestGraph.INSTANCE.statusLine(),
            AGCGlobalFairnessMatrix.INSTANCE.statusLine(),
            AGCSharedReadOnlyCache.INSTANCE.statusLine(),
            "rule=no packet drops, no FIFO skips, no off-thread Bukkit mutation, no entity tick skipping"
        );
    }

    public static String survivalScale() {
        return String.join("\n",
            "AGC survival scale target: ordinary survival, thousands of players, 20 TPS, no semantic invasion",
            AGCSurvivalScaleCoordinator.INSTANCE.statusLine(),
            AGCResourceEfficiencyEngine.INSTANCE.statusLine(),
            AGCInterestGraph.INSTANCE.statusLine(),
            AGCGlobalFairnessMatrix.INSTANCE.statusLine(),
            AGCSharedReadOnlyCache.INSTANCE.statusLine(),
            AGCComputeTopologyPlanner.INSTANCE.statusLine(),
            AGCFanoutDeduplicator.INSTANCE.statusLine(),
            AGCChunkIntentPlanner.INSTANCE.statusLine(),
            AGCEntityVisibilityPreselector.INSTANCE.statusLine(),
            AGCStorageIoGovernor.INSTANCE.statusLine(),
            AGCScaleKernel.INSTANCE.statusLine(),
            AGCRegionHotspotMap.INSTANCE.statusLine(),
            AGCNetworkFanoutKernel.INSTANCE.statusLine(),
            AGCChunkPipelineKernel.INSTANCE.statusLine(),
            AGCEntityTrackerKernel.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            AGCPerformanceStandard.INSTANCE.statusLine(),
            "strategy=deduplicate read-only fanout, budget by CPU/memory pressure, preserve visible order, never skip gameplay ticks"
        );
    }

    public static String resources() {
        return String.join("\n",
            "AGC compute resource efficiency:",
            AGCResourceEfficiencyEngine.INSTANCE.statusLine(),
            AGCTickBudgetArbiter.INSTANCE.statusLine(),
            AGCGlobalFairnessMatrix.INSTANCE.statusLine(),
            AGCComputeTopologyPlanner.INSTANCE.statusLine(),
            AGCFanoutDeduplicator.INSTANCE.statusLine(),
            AGCChunkIntentPlanner.INSTANCE.statusLine(),
            AGCEntityVisibilityPreselector.INSTANCE.statusLine(),
            AGCStorageIoGovernor.INSTANCE.statusLine(),
            AGCScaleKernel.INSTANCE.statusLine(),
            AGCRegionHotspotMap.INSTANCE.statusLine(),
            AGCNetworkFanoutKernel.INSTANCE.statusLine(),
            AGCChunkPipelineKernel.INSTANCE.statusLine(),
            AGCEntityTrackerKernel.INSTANCE.statusLine(),
            AGCThreadAffinityTranslator.INSTANCE.statusLine(),
            AGCLatencySLO.INSTANCE.statusLine(),
            "visibleCommitPolicy=ordered commits are not resource-blocked; invisible prepare work is shaped instead"
        );
    }

    public static String topology() {
        return String.join("\n",
            "AGC alpha14 topology/no-invasion scale engine:",
            AGCComputeTopologyPlanner.INSTANCE.statusLine(),
            AGCFanoutDeduplicator.INSTANCE.statusLine(),
            AGCChunkIntentPlanner.INSTANCE.statusLine(),
            AGCEntityVisibilityPreselector.INSTANCE.statusLine(),
            AGCStorageIoGovernor.INSTANCE.statusLine(),
            AGCScaleKernel.INSTANCE.statusLine(),
            AGCRegionHotspotMap.INSTANCE.statusLine(),
            AGCNetworkFanoutKernel.INSTANCE.statusLine(),
            AGCChunkPipelineKernel.INSTANCE.statusLine(),
            AGCEntityTrackerKernel.INSTANCE.statusLine(),
            AGCThreadAffinityTranslator.INSTANCE.statusLine(),
            AGCPluginLogicTranslator.INSTANCE.statusLine(),
            AGCScaleControlPlane.INSTANCE.statusLine(),
            AGCPacketShapeTable.INSTANCE.statusLine(),
            AGCPlayerCohortTable.INSTANCE.statusLine(),
            AGCChunkIntentCompactor.INSTANCE.statusLine(),
            AGCEntityBandingPlanner.INSTANCE.statusLine(),
            AGCMemoryLocalityPlanner.INSTANCE.statusLine(),
            AGCEncodingReusePlanner.INSTANCE.statusLine(),
            AGCPluginBackpressureBridge.INSTANCE.statusLine(),
            AGCHotspotEvictionPlanner.INSTANCE.statusLine(),
            AGCSurvivalScaleCoordinator.INSTANCE.statusLine(),
            AGCResourceEfficiencyEngine.INSTANCE.statusLine(),
            "rule=use more cores only for read-only prepare/fanout/compression/chunk-intent helpers; ordered gameplay commits stay semantic-preserving"
        );
    }

    public static String wildScale() {
        return String.join("\n",
            "AGC alpha14 wild-scale kernel: ordinary survival, target thousands of players at 20 TPS",
            AGCScaleKernel.INSTANCE.statusLine(),
            AGCRegionHotspotMap.INSTANCE.statusLine(),
            AGCNetworkFanoutKernel.INSTANCE.statusLine(),
            AGCChunkPipelineKernel.INSTANCE.statusLine(),
            AGCEntityTrackerKernel.INSTANCE.statusLine(),
            AGCThreadAffinityTranslator.INSTANCE.statusLine(),
            AGCPluginLogicTranslator.INSTANCE.statusLine(),
            AGCScaleControlPlane.INSTANCE.statusLine(),
            AGCPacketShapeTable.INSTANCE.statusLine(),
            AGCPlayerCohortTable.INSTANCE.statusLine(),
            AGCChunkIntentCompactor.INSTANCE.statusLine(),
            AGCEntityBandingPlanner.INSTANCE.statusLine(),
            AGCMemoryLocalityPlanner.INSTANCE.statusLine(),
            AGCEncodingReusePlanner.INSTANCE.statusLine(),
            AGCPluginBackpressureBridge.INSTANCE.statusLine(),
            AGCHotspotEvictionPlanner.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            "hardRules=no packet drops, no entity tick skip, no chunk FIFO skip, no off-thread Bukkit mutation, no plugin event reorder",
            "bigOptimisations=fanout shape dedupe, chunk pipeline intent tokens, entity candidate snapshots, region hotspot sharing, CPU reserve scale kernel"
        );
    }

    public static String scale15() {
        return String.join("\n",
            "AGC alpha15 thousand-player survival optimisation plane:",
            AGCScaleControlPlane.INSTANCE.statusLine(),
            AGCPacketShapeTable.INSTANCE.statusLine(),
            AGCPlayerCohortTable.INSTANCE.statusLine(),
            AGCChunkIntentCompactor.INSTANCE.statusLine(),
            AGCEntityBandingPlanner.INSTANCE.statusLine(),
            AGCMemoryLocalityPlanner.INSTANCE.statusLine(),
            AGCEncodingReusePlanner.INSTANCE.statusLine(),
            AGCPluginBackpressureBridge.INSTANCE.statusLine(),
            AGCHotspotEvictionPlanner.INSTANCE.statusLine(),
            AGCScaleKernel.INSTANCE.statusLine(),
            AGCNetworkFanoutKernel.INSTANCE.statusLine(),
            AGCChunkPipelineKernel.INSTANCE.statusLine(),
            AGCEntityTrackerKernel.INSTANCE.statusLine(),
            "hardRules=no packet drops, no per-connection reorder, no chunk FIFO skip, no entity tick/AI/damage skip, no off-thread Bukkit mutation",
            "strategy=packet-shape reuse, player cohorts, chunk-intent compaction, entity banding, plugin backpressure and memory-local scratch planning"
        );
    }

    public static String scale16() {
        return String.join("\n",
            "AGC alpha16 logic/algorithm optimisation plane:",
            AGCScale16ControlLaw.INSTANCE.statusLine(),
            AGCPlayerMotionIntentModel.INSTANCE.statusLine(),
            AGCChunkLookaheadPlanner.INSTANCE.statusLine(),
            AGCEntitySpatialBandIndex.INSTANCE.statusLine(),
            AGCNetworkDeltaShapePlanner.INSTANCE.statusLine(),
            AGCWorldLogicPhaseDAG.INSTANCE.statusLine(),
            AGCPluginSemanticModel.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            "hardRules=no packet drops, no chunk FIFO skip, no entity tick skip, no off-thread Bukkit mutation, no plugin event reorder, no redstone/factory phase reorder",
            "algorithm=PID-like tick control + motion lookahead + stable spatial bands + lossless delta shapes + Minecraft phase DAG"
        );
    }


    public static String scale17() {
        return String.join("\n",
            "AGC alpha17 semantic-preserving algorithm engine:",
            AGCScale17AlgorithmKernel.INSTANCE.statusLine(),
            AGCChunkPredictiveIndex.INSTANCE.statusLine(),
            AGCEntityInteractionGraph.INSTANCE.statusLine(),
            AGCNetworkRecipientCohortGraph.INSTANCE.statusLine(),
            AGCWorldPhaseExecutorPlan.INSTANCE.statusLine(),
            AGCPluginContractVerifier.INSTANCE.statusLine(),
            AGCCacheLocalityWindow.INSTANCE.statusLine(),
            AGCScale18ControlPlane.INSTANCE.statusLine(),
            AGCUnifiedTickPlanCompiler.INSTANCE.statusLine(),
            AGCNetworkSendGraphCompiler.INSTANCE.statusLine(),
            AGCChunkSpatialRoutePlanner.INSTANCE.statusLine(),
            AGCEntityTrackerDeltaIndex.INSTANCE.statusLine(),
            AGCReadOnlyWorkStealingPlanner.INSTANCE.statusLine(),
            AGCPluginSemanticFirewall.INSTANCE.statusLine(),
            AGCScale16ControlLaw.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            "hardRules=no packet drops, no connection reorder, no chunk FIFO skip, no entity tick/AI/damage skip, no off-thread Bukkit mutation, no plugin event reorder",
            "algorithm=predictive chunk rings + entity interaction graph + recipient cohort graph + phase executor compiler + plugin contract verifier"
        );
    }

    public static String scale18() {
        return String.join("\n",
            "AGC alpha18 integrated semantic tick planner:",
            AGCScale18ControlPlane.INSTANCE.statusLine(),
            AGCUnifiedTickPlanCompiler.INSTANCE.statusLine(),
            AGCNetworkSendGraphCompiler.INSTANCE.statusLine(),
            AGCChunkSpatialRoutePlanner.INSTANCE.statusLine(),
            AGCEntityTrackerDeltaIndex.INSTANCE.statusLine(),
            AGCReadOnlyWorkStealingPlanner.INSTANCE.statusLine(),
            AGCPluginSemanticFirewall.INSTANCE.statusLine(),
            AGCScale17AlgorithmKernel.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            "hardRules=no packet drops, no mutable packet sharing, no connection reorder, no chunk FIFO skip, no entity tick/AI/damage skip, no off-thread Bukkit mutation",
            "algorithm=unified tick plan + lossless send graph + stable chunk route keys + entity delta index + plugin semantic firewall + read-only helper work planning"
        );
    }

    public static String scale19() {
        return String.join("\n",
            "AGC alpha19 logic algorithm optimiser:",
            AGCScale19LogicKernel.INSTANCE.statusLine(),
            AGCNetworkMulticastShapeGraph.INSTANCE.statusLine(),
            AGCChunkDemandForecastTable.INSTANCE.statusLine(),
            AGCEntityInterestSetReducer.INSTANCE.statusLine(),
            AGCWorldPhaseBatchCompiler.INSTANCE.statusLine(),
            AGCPluginSemanticJit.INSTANCE.statusLine(),
            AGCAdaptiveLocalityRing.INSTANCE.statusLine(),
            AGCUnifiedTickPlanCompiler.INSTANCE.statusLine(),
            AGCScale18ControlPlane.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            "hardRules=no packet drops, no mutable packet sharing, no connection reorder, no chunk FIFO skip, no entity tick/AI/damage skip, no off-thread Bukkit mutation",
            "algorithm=lossless multicast shape graph + chunk demand forecast table + entity interest set reduction + read-only world phase batch compiler + semantic JIT cache + adaptive locality ring"
        );
    }

    public static String scale20() {
        return String.join("\n",
            "AGC alpha20 lossless scale pipeline: big optimisations first, semantic rules always preserved",
            AGCScale20LosslessPipeline.INSTANCE.statusLine(),
            AGCNetworkBackboneGraph.INSTANCE.statusLine(),
            AGCChunkWorksetSlicer.INSTANCE.statusLine(),
            AGCEntityWitnessIndex.INSTANCE.statusLine(),
            AGCWorldSemanticScheduler.INSTANCE.statusLine(),
            AGCPluginDeterminismGuard.INSTANCE.statusLine(),
            AGCResourcePlacementPlanner.INSTANCE.statusLine(),
            AGCScale19LogicKernel.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            "hardRules=no packet drops, no mutable packet sharing, no connection reorder, no chunk FIFO skip, no entity tick/AI/damage skip, no off-thread Bukkit mutation, no plugin event reorder",
            "algorithm=lossless scale pipeline + network backbone graph + FIFO-compatible chunk worksets + read-only entity witness index + semantic world scheduler + deterministic plugin guard + CPU/memory resource placement"
        );
    }


    public static String scale21() {
        return String.join("\n",
            "AGC alpha21 critical-path survival runtime: bigger algorithmic optimisations while preserving Minecraft semantics",
            AGCScale21CriticalPathRuntime.INSTANCE.statusLine(),
            AGCPlayerClusterMovementPlanner.INSTANCE.statusLine(),
            AGCChunkStormController.INSTANCE.statusLine(),
            AGCEntityDensityField.INSTANCE.statusLine(),
            AGCNetworkBroadcastPlanner.INSTANCE.statusLine(),
            AGCPluginCommitSequencer.INSTANCE.statusLine(),
            AGCMultiverseLaneAllocator.INSTANCE.statusLine(),
            AGCComputeWasteReducer.INSTANCE.statusLine(),
            AGCScale20LosslessPipeline.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            "hardRules=no packet drops, no mutable packet sharing, no connection reorder, no chunk FIFO skip, no entity tick/AI/damage skip, no off-thread Bukkit mutation, no plugin event reorder",
            "algorithm=critical-path runtime + player cluster movement planning + chunk storm smoothing + entity density fields + lossless broadcast planning + plugin commit sequencing + multiverse lane allocation + compute waste reduction"
        );
    }


    public static String scale22() {
        return String.join("\n",
            "AGC alpha22 tick-work compiler: large semantic-preserving performance pass",
            AGCScale22TickWorkCompiler.INSTANCE.statusLine(),
            AGCNetworkCohortBackboneCache.INSTANCE.statusLine(),
            AGCChunkTerrainDemandModel.INSTANCE.statusLine(),
            AGCEntityObserverMatrix.INSTANCE.statusLine(),
            AGCPluginOrderedCommitLedger.INSTANCE.statusLine(),
            AGCMultiversePhaseMatrix.INSTANCE.statusLine(),
            AGCSystemLoadShepherd.INSTANCE.statusLine(),
            AGCSemanticHotPathMeter.INSTANCE.statusLine(),
            AGCScale21CriticalPathRuntime.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            "hardRules=no packet drops, no mutable packet sharing, no connection reorder, no chunk FIFO skip, no entity tick/AI/damage skip, no off-thread Bukkit mutation, no plugin event reorder",
            "algorithm=central tick-work compiler + packet cohort backbone cache + terrain demand model + entity observer matrix + ordered plugin commit ledger + multiverse phase matrix + system load shepherd"
        );
    }


    public static String scale23() {
        return String.join("\n",
            "AGC alpha23 compiled dispatch and stable-diff runtime: large algorithmic pass with strict semantic preservation",
            AGCScale23DispatchTable.INSTANCE.statusLine(),
            AGCNetworkStableDiffPlanner.INSTANCE.statusLine(),
            AGCChunkFrontierScheduler.INSTANCE.statusLine(),
            AGCEntityVisibilityLattice.INSTANCE.statusLine(),
            AGCPluginCommitCoalescer.INSTANCE.statusLine(),
            AGCWorldBarrierDAGCompiler.INSTANCE.statusLine(),
            AGCWorkerRoutePlanner.INSTANCE.statusLine(),
            AGCScale22TickWorkCompiler.INSTANCE.statusLine(),
            AGCSemanticHotPathMeter.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            "hardRules=no packet drops, no mutable packet sharing, no connection reorder, no chunk FIFO skip, no entity tick/AI/damage skip, no off-thread Bukkit mutation, no plugin event reorder",
            "algorithm=compiled dispatch table + stable packet diff vectors + FIFO chunk frontier scheduler + entity visibility lattice + plugin commit ticket coalescer + world barrier DAG compiler + read-only worker routing"
        );
    }


    public static String scale24() {
        return String.join("\n",
            "AGC alpha24 hot-path compiled plan cache: bigger semantic-preserving optimisation pass",
            AGCScale24HotPathRuntime.INSTANCE.statusLine(),
            AGCHotPathPlanCache.INSTANCE.statusLine(),
            AGCNetworkOrderVectorCompiler.INSTANCE.statusLine(),
            AGCChunkHorizonCompiler.INSTANCE.statusLine(),
            AGCEntityObserverSetCompiler.INSTANCE.statusLine(),
            AGCWorldReadOnlyBatchGraph.INSTANCE.statusLine(),
            AGCPluginDeterministicTicketCache.INSTANCE.statusLine(),
            AGCResourceLocalityScheduler.INSTANCE.statusLine(),
            AGCScale23DispatchTable.INSTANCE.statusLine(),
            AGCSemanticHotPathMeter.INSTANCE.statusLine(),
            AGCNoInvasionOptimizer.INSTANCE.statusLine(),
            "hardRules=no packet drops, no mutable packet sharing, no connection reorder, no chunk FIFO skip, no entity tick/AI/damage skip, no Bukkit off-thread mutation, no plugin event reorder",
            "algorithm=hot-path runtime credits + tick-local compiled plan cache + network order vectors + FIFO chunk horizons + entity observer-set compiler + world read-only batch graph + deterministic plugin ticket cache + resource locality scheduler"
        );
    }

    public static String minigameApi() {
        try {
            final Object service = io.agcmc.agc.api.AGC.minigames();
            final int arenas = io.agcmc.agc.api.AGC.minigames().arenas().size();
            return "AGC Mini-game API: enabled service=" + service.getClass().getName()
                + " arenas=" + arenas
                + " events=join,leave,start,end,team-assign"
                + " contracts=" + io.agcmc.agc.api.AGC.compatibility().declarationCount()
                + " helpers=arena,bounds,teams,loadout,snapshot,countdown,scaling-plan,primary-thread-scheduler,read-only-prepare";
        } catch (final Throwable throwable) {
            return "AGC Mini-game API: unavailable: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
    }
}
