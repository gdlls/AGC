package net.minecraft.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Semantic-preserving coordinator for AGC alpha9.
 * <p>
 * This is the opposite of a semantic-first switch. Every optimisation declares
 * which invisible lane it wants to use: read-only prepare, ordered commit,
 * fair FIFO queueing, or deadline-ordered batching. The coordinator never grants
 * an operation that would drop data, reorder a per-player stream, move Bukkit
 * callbacks away from their ordered commit lane, or extend interactive latency
 * beyond the configured deadline.
 */
public final class AGCNoInvasionOptimizer {
    public static final AGCNoInvasionOptimizer INSTANCE = new AGCNoInvasionOptimizer();

    public enum Lane {
        NETWORK_DEADLINE_BATCH,
        CHUNK_FIFO_FAIR_QUEUE,
        ENTITY_READ_ONLY_SNAPSHOT,
        WORLD_WRITE_INTENT_WAVE,
        PLAYER_FLOW,
        PLUGIN_TRANSLATION_COMMIT
    }

    public enum Decision {
        IMMEDIATE_ORDERED,
        DEADLINE_ORDERED_BATCH,
        FIFO_FAIR_WAIT,
        READ_ONLY_PREPARE,
        CONFLICT_FREE_WAVE,
        ORDERED_COMMIT
    }

    private final EnumMap<Lane, AtomicLong> requests = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> immediateOrdered = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> deadlineBatched = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> fifoWaits = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> readOnlyPrepares = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> conflictFreeWaves = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> orderedCommits = new EnumMap<>(Lane.class);

    private volatile boolean enabled = true;
    private volatile boolean semanticStrictMode = true;
    private volatile int maxNetworkDeadlineTicks = 1;
    private volatile long maxDeferredFlushPending = 32_768L;
    private volatile long maxTranslatorPending = 8_192L;
    private volatile long tickSequence;
    private volatile Decision lastNetworkDecision = Decision.IMMEDIATE_ORDERED;
    private volatile Decision lastChunkDecision = Decision.ORDERED_COMMIT;
    private volatile Decision lastEntityDecision = Decision.READ_ONLY_PREPARE;
    private volatile Decision lastWorldDecision = Decision.ORDERED_COMMIT;

    private AGCNoInvasionOptimizer() {
        for (final Lane lane : Lane.values()) {
            this.requests.put(lane, new AtomicLong());
            this.immediateOrdered.put(lane, new AtomicLong());
            this.deadlineBatched.put(lane, new AtomicLong());
            this.fifoWaits.put(lane, new AtomicLong());
            this.readOnlyPrepares.put(lane, new AtomicLong());
            this.conflictFreeWaves.put(lane, new AtomicLong());
            this.orderedCommits.put(lane, new AtomicLong());
        }
    }

    public void configure(
        final boolean enabled,
        final boolean semanticStrictMode,
        final int maxNetworkDeadlineTicks,
        final long maxDeferredFlushPending,
        final long maxTranslatorPending
    ) {
        this.enabled = enabled;
        this.semanticStrictMode = semanticStrictMode;
        this.maxNetworkDeadlineTicks = Math.max(0, maxNetworkDeadlineTicks);
        this.maxDeferredFlushPending = Math.max(1L, maxDeferredFlushPending);
        this.maxTranslatorPending = Math.max(1L, maxTranslatorPending);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        AGCChunkBudget.INSTANCE.beginTick(sequence);
        AGCChunkFairQueue.INSTANCE.beginTick(sequence);
        AGCEntitySnapshotPlanner.INSTANCE.beginTick(sequence);
        AGCPlayerIntentScheduler.INSTANCE.beginTick(sequence);
        AGCFanoutDeduplicator.INSTANCE.beginTick(sequence);
        AGCChunkIntentPlanner.INSTANCE.beginTick(sequence);
        AGCEntityVisibilityPreselector.INSTANCE.beginTick(sequence);
        AGCStorageIoGovernor.INSTANCE.beginTick(sequence);
        AGCNetworkFanoutKernel.INSTANCE.beginTick(sequence);
        AGCChunkPipelineKernel.INSTANCE.beginTick(sequence);
        AGCEntityTrackerKernel.INSTANCE.beginTick(sequence);
        AGCThreadAffinityTranslator.INSTANCE.beginTick(sequence);
        AGCPacketShapeTable.INSTANCE.beginTick(sequence);
        AGCPlayerCohortTable.INSTANCE.beginTick(sequence);
        AGCChunkIntentCompactor.INSTANCE.beginTick(sequence);
        AGCEntityBandingPlanner.INSTANCE.beginTick(sequence);
        AGCMemoryLocalityPlanner.INSTANCE.beginTick(sequence);
        AGCEncodingReusePlanner.INSTANCE.beginTick(sequence);
        AGCHotspotEvictionPlanner.INSTANCE.beginTick(sequence);
        AGCPlayerMotionIntentModel.INSTANCE.beginTick(sequence);
        AGCChunkLookaheadPlanner.INSTANCE.beginTick(sequence);
        AGCEntitySpatialBandIndex.INSTANCE.beginTick(sequence);
        AGCNetworkDeltaShapePlanner.INSTANCE.beginTick(sequence);
        AGCWorldLogicPhaseDAG.INSTANCE.beginTick(sequence);
        AGCPluginSemanticModel.INSTANCE.beginTick(sequence);
        AGCScale17AlgorithmKernel.INSTANCE.beginTick(sequence);
        AGCChunkPredictiveIndex.INSTANCE.beginTick(sequence);
        AGCEntityInteractionGraph.INSTANCE.beginTick(sequence);
        AGCNetworkRecipientCohortGraph.INSTANCE.beginTick(sequence);
        AGCWorldPhaseExecutorPlan.INSTANCE.beginTick(sequence);
        AGCPluginContractVerifier.INSTANCE.beginTick(sequence);
        AGCCacheLocalityWindow.INSTANCE.beginTick(sequence);
        AGCUnifiedTickPlanCompiler.INSTANCE.beginTick(sequence);
        AGCChunkSpatialRoutePlanner.INSTANCE.beginTick(sequence);
        AGCEntityTrackerDeltaIndex.INSTANCE.beginTick(sequence);
        AGCNetworkSendGraphCompiler.INSTANCE.beginTick(sequence);
        AGCReadOnlyWorkStealingPlanner.INSTANCE.beginTick(sequence);
        AGCPluginSemanticFirewall.INSTANCE.beginTick(sequence);
        AGCScale18ControlPlane.INSTANCE.beginTick(sequence);
        AGCScale19LogicKernel.INSTANCE.beginTick(sequence);
        AGCNetworkMulticastShapeGraph.INSTANCE.beginTick(sequence);
        AGCChunkDemandForecastTable.INSTANCE.beginTick(sequence);
        AGCEntityInterestSetReducer.INSTANCE.beginTick(sequence);
        AGCWorldPhaseBatchCompiler.INSTANCE.beginTick(sequence);
        AGCPluginSemanticJit.INSTANCE.beginTick(sequence);
        AGCAdaptiveLocalityRing.INSTANCE.beginTick(sequence);
        AGCScale20LosslessPipeline.INSTANCE.beginTick(sequence);
        AGCNetworkBackboneGraph.INSTANCE.beginTick(sequence);
        AGCChunkWorksetSlicer.INSTANCE.beginTick(sequence);
        AGCEntityWitnessIndex.INSTANCE.beginTick(sequence);
        AGCWorldSemanticScheduler.INSTANCE.beginTick(sequence);
        AGCPluginDeterminismGuard.INSTANCE.beginTick(sequence);
        AGCResourcePlacementPlanner.INSTANCE.beginTick(sequence);
        AGCScale21CriticalPathRuntime.INSTANCE.beginTick(sequence);
        AGCPlayerClusterMovementPlanner.INSTANCE.beginTick(sequence);
        AGCChunkStormController.INSTANCE.beginTick(sequence);
        AGCEntityDensityField.INSTANCE.beginTick(sequence);
        AGCNetworkBroadcastPlanner.INSTANCE.beginTick(sequence);
        AGCPluginCommitSequencer.INSTANCE.beginTick(sequence);
        AGCMultiverseLaneAllocator.INSTANCE.beginTick(sequence);
        AGCComputeWasteReducer.INSTANCE.beginTick(sequence);
        AGCScale22TickWorkCompiler.INSTANCE.beginTick(sequence);
        AGCNetworkCohortBackboneCache.INSTANCE.beginTick(sequence);
        AGCChunkTerrainDemandModel.INSTANCE.beginTick(sequence);
        AGCEntityObserverMatrix.INSTANCE.beginTick(sequence);
        AGCPluginOrderedCommitLedger.INSTANCE.beginTick(sequence);
        AGCMultiversePhaseMatrix.INSTANCE.beginTick(sequence);
        AGCSystemLoadShepherd.INSTANCE.beginTick(sequence);
        AGCSemanticHotPathMeter.INSTANCE.beginTick(sequence);
        AGCScale23DispatchTable.INSTANCE.beginTick(sequence);
        AGCNetworkStableDiffPlanner.INSTANCE.beginTick(sequence);
        AGCChunkFrontierScheduler.INSTANCE.beginTick(sequence);
        AGCEntityVisibilityLattice.INSTANCE.beginTick(sequence);
        AGCPluginCommitCoalescer.INSTANCE.beginTick(sequence);
        AGCWorldBarrierDAGCompiler.INSTANCE.beginTick(sequence);
        AGCWorkerRoutePlanner.INSTANCE.beginTick(sequence);
        AGCScale24HotPathRuntime.INSTANCE.beginTick(sequence);
        AGCHotPathPlanCache.INSTANCE.beginTick(sequence);
        AGCNetworkOrderVectorCompiler.INSTANCE.beginTick(sequence);
        AGCChunkHorizonCompiler.INSTANCE.beginTick(sequence);
        AGCEntityObserverSetCompiler.INSTANCE.beginTick(sequence);
        AGCWorldReadOnlyBatchGraph.INSTANCE.beginTick(sequence);
        AGCPluginDeterministicTicketCache.INSTANCE.beginTick(sequence);
        AGCResourceLocalityScheduler.INSTANCE.beginTick(sequence);
    }

    public Decision admitNetworkDeadlineBatch(final String packetName, final boolean interactive, final long pendingDeferredFlushes) {
        this.requests.get(Lane.NETWORK_DEADLINE_BATCH).incrementAndGet();
        if (!this.enabled || interactive || this.maxNetworkDeadlineTicks <= 0 || pendingDeferredFlushes >= this.maxDeferredFlushPending) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, packetName);
            return this.lastNetworkDecision;
        }
        final AGCNetworkCohortBackboneCache.Plan cohortBackbone = AGCNetworkCohortBackboneCache.INSTANCE.plan(
            packetName,
            Math.max(1L, pendingDeferredFlushes),
            interactive,
            this.maxNetworkDeadlineTicks
        );
        if (cohortBackbone.orderedNow()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, cohortBackbone.reason());
            return this.lastNetworkDecision;
        }
        final AGCScale22TickWorkCompiler.Compile compiledNetwork = AGCScale22TickWorkCompiler.INSTANCE.compile(
            AGCScale22TickWorkCompiler.Track.NETWORK_SEND_GRAPH,
            cohortBackbone.cost(),
            false,
            cohortBackbone.reason()
        );
        if (!compiledNetwork.compiled() && pendingDeferredFlushes > 12_288L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, compiledNetwork.reason());
            return this.lastNetworkDecision;
        }
        final AGCSystemLoadShepherd.Permit networkLoad = AGCSystemLoadShepherd.INSTANCE.permit("network", cohortBackbone.cost(), false);
        if (!networkLoad.admitted() && pendingDeferredFlushes > 16_384L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, networkLoad.reason());
            return this.lastNetworkDecision;
        }
        AGCSemanticHotPathMeter.INSTANCE.record(
            AGCSemanticHotPathMeter.Kind.NETWORK_BACKBONE,
            Math.max(1L, pendingDeferredFlushes),
            Math.max(1L, cohortBackbone.cohorts() + cohortBackbone.backboneNodes()),
            packetName
        );
        final AGCNetworkStableDiffPlanner.Plan stableDiff = AGCNetworkStableDiffPlanner.INSTANCE.plan(
            packetName,
            Math.max(1L, pendingDeferredFlushes),
            interactive,
            this.maxNetworkDeadlineTicks
        );
        if (stableDiff.orderedNow()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, stableDiff.reason());
            return this.lastNetworkDecision;
        }
        final AGCNetworkOrderVectorCompiler.Plan orderVector = AGCNetworkOrderVectorCompiler.INSTANCE.compile(
            packetName,
            Math.max(1L, pendingDeferredFlushes),
            interactive,
            this.maxNetworkDeadlineTicks
        );
        if (orderVector.orderedNow()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, orderVector.reason());
            return this.lastNetworkDecision;
        }
        final AGCScale24HotPathRuntime.Grant networkHotPath = AGCScale24HotPathRuntime.INSTANCE.claim(
            AGCScale24HotPathRuntime.Domain.NETWORK_ORDER_VECTOR,
            Math.max(1L, stableDiff.cost() + orderVector.cost()),
            false,
            orderVector.reason()
        );
        if (!networkHotPath.admitted() && pendingDeferredFlushes > 16_384L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, networkHotPath.reason());
            return this.lastNetworkDecision;
        }
        final AGCHotPathPlanCache.Plan cachedNetworkPlan = AGCHotPathPlanCache.INSTANCE.compile(
            AGCHotPathPlanCache.Kind.NETWORK_VECTOR,
            orderVector.vectorKey(),
            Math.max(1L, orderVector.cost()),
            false,
            orderVector.reason()
        );
        if (!cachedNetworkPlan.compiled() && pendingDeferredFlushes > 12_288L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, cachedNetworkPlan.reason());
            return this.lastNetworkDecision;
        }
        final AGCResourceLocalityScheduler.Slot networkLocality24 = AGCResourceLocalityScheduler.INSTANCE.place(
            "network-order-vector:" + packetName,
            Math.max(4096L, orderVector.lanes() * 256L),
            false
        );
        if (!networkLocality24.placed() && pendingDeferredFlushes > 16_384L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, networkLocality24.reason());
            return this.lastNetworkDecision;
        }
        final AGCScale23DispatchTable.Dispatch networkDispatch = AGCScale23DispatchTable.INSTANCE.compile(
            AGCScale23DispatchTable.Lane.NETWORK_DIFF_VECTOR,
            stableDiff.cost(),
            false,
            stableDiff.reason()
        );
        if (!networkDispatch.compiled() && pendingDeferredFlushes > 8192L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, networkDispatch.reason());
            return this.lastNetworkDecision;
        }
        final AGCWorkerRoutePlanner.Route networkRoute = AGCWorkerRoutePlanner.INSTANCE.route("network-diff:" + packetName, stableDiff.cost(), false);
        if (!networkRoute.routed() && pendingDeferredFlushes > 12288L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, networkRoute.reason());
            return this.lastNetworkDecision;
        }
        final long networkCost = 1L + Math.max(0L, pendingDeferredFlushes / 256L);
        final AGCScale21CriticalPathRuntime.Grant criticalNetwork = AGCScale21CriticalPathRuntime.INSTANCE.claim(
            AGCScale21CriticalPathRuntime.Domain.NETWORK_FANOUT,
            Math.max(1L, pendingDeferredFlushes / 32L),
            interactive,
            packetName
        );
        if (!criticalNetwork.admitted() && !criticalNetwork.orderedVisible() && pendingDeferredFlushes > 16_384L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, criticalNetwork.reason());
            return this.lastNetworkDecision;
        }
        final AGCNetworkBroadcastPlanner.Plan broadcastPlan = AGCNetworkBroadcastPlanner.INSTANCE.plan(
            packetName,
            Math.max(1L, pendingDeferredFlushes),
            interactive,
            this.maxNetworkDeadlineTicks
        );
        if (broadcastPlan.orderedNow()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, broadcastPlan.reason());
            return this.lastNetworkDecision;
        }
        AGCComputeWasteReducer.INSTANCE.record(
            AGCComputeWasteReducer.Kind.NETWORK_SHAPE,
            Math.max(1L, pendingDeferredFlushes),
            Math.max(1L, broadcastPlan.cohorts()),
            packetName
        );
        final AGCResourcePlacementPlanner.Placement networkPlacement = AGCResourcePlacementPlanner.INSTANCE.place("network:" + packetName, networkCost, false);
        if (!networkPlacement.placed() && pendingDeferredFlushes > 8192L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, networkPlacement.reason());
            return this.lastNetworkDecision;
        }
        final AGCNetworkBackboneGraph.Plan backbonePlan = AGCNetworkBackboneGraph.INSTANCE.compile(
            packetName,
            (int) Math.min(Integer.MAX_VALUE, Math.max(1L, pendingDeferredFlushes)),
            interactive,
            pendingDeferredFlushes
        );
        if (backbonePlan.orderedNow()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, backbonePlan.reason());
            return this.lastNetworkDecision;
        }
        final AGCNetworkMulticastShapeGraph.Plan multicastShape = AGCNetworkMulticastShapeGraph.INSTANCE.compile(
            packetName,
            (int) Math.min(Integer.MAX_VALUE, Math.max(1L, pendingDeferredFlushes)),
            interactive,
            pendingDeferredFlushes
        );
        if (multicastShape.orderedNow()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, multicastShape.reason());
            return this.lastNetworkDecision;
        }
        final AGCAdaptiveLocalityRing.Admission networkRing = AGCAdaptiveLocalityRing.INSTANCE.claim(
            "network-shape:" + packetName,
            Math.max(2048L, multicastShape.groups() * 512L)
        );
        if (!networkRing.admitted() && pendingDeferredFlushes > 4096L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, networkRing.reason());
            return this.lastNetworkDecision;
        }
        final AGCNetworkSendGraphCompiler.Plan sendGraph = AGCNetworkSendGraphCompiler.INSTANCE.compile(
            packetName,
            (int) Math.min(Integer.MAX_VALUE, Math.max(1L, pendingDeferredFlushes)),
            interactive,
            pendingDeferredFlushes
        );
        if (sendGraph.orderedNow()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, sendGraph.reason());
            return this.lastNetworkDecision;
        }
        final AGCNetworkDeltaShapePlanner.Decision deltaShape = AGCNetworkDeltaShapePlanner.INSTANCE.plan(packetName, interactive, Math.max(1L, pendingDeferredFlushes), pendingDeferredFlushes);
        if (deltaShape.orderedNow()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, deltaShape.reason());
            return this.lastNetworkDecision;
        }
        final AGCWorldLogicPhaseDAG.Decision networkPhase = AGCWorldLogicPhaseDAG.INSTANCE.classify(AGCWorldLogicPhaseDAG.Phase.NETWORK_FANOUT_PLAN, packetName);
        if (networkPhase.orderedBarrier()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, networkPhase.reason());
            return this.lastNetworkDecision;
        }
        final AGCNetworkRecipientCohortGraph.Plan recipientCohort = AGCNetworkRecipientCohortGraph.INSTANCE.plan(
            packetName,
            interactive,
            Math.max(1L, pendingDeferredFlushes),
            1
        );
        if (!recipientCohort.admitted() && pendingDeferredFlushes > 2048L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, recipientCohort.reason());
            return this.lastNetworkDecision;
        }
        final AGCCacheLocalityWindow.Admission networkLocality = AGCCacheLocalityWindow.INSTANCE.claim(
            "network-cohort:" + packetName,
            Math.max(4096L, pendingDeferredFlushes * 12L)
        );
        if (!networkLocality.admitted() && pendingDeferredFlushes > 4096L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, networkLocality.reason());
            return this.lastNetworkDecision;
        }
        final AGCPacketShapeTable.Decision packetShape = AGCPacketShapeTable.INSTANCE.plan(
            packetName,
            AGCPacketBudget.Priority.COSMETIC,
            (int) Math.min(Integer.MAX_VALUE, 256L + pendingDeferredFlushes),
            (int) Math.min(Integer.MAX_VALUE, Math.max(1L, pendingDeferredFlushes))
        );
        if (packetShape.orderedNow()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, packetShape.reason());
            return this.lastNetworkDecision;
        }
        final AGCEncodingReusePlanner.Decision encodingDecision = AGCEncodingReusePlanner.INSTANCE.plan(packetName, (int) Math.min(Integer.MAX_VALUE, 512L + pendingDeferredFlushes), false);
        if (!encodingDecision.sharedEncoding() && pendingDeferredFlushes > 1024L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, encodingDecision.reason());
            return this.lastNetworkDecision;
        }
        final AGCMemoryLocalityPlanner.Admission networkScratch = AGCMemoryLocalityPlanner.INSTANCE.claimScratch("network:" + packetName, Math.max(1024L, pendingDeferredFlushes * 8L), "network fanout scratch");
        if (!networkScratch.admitted() && pendingDeferredFlushes > 4096L) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, networkScratch.reason());
            return this.lastNetworkDecision;
        }
        final AGCNetworkFanoutKernel.Admission fanoutKernel = AGCNetworkFanoutKernel.INSTANCE.admit(
            packetName,
            interactive,
            (int) Math.min(Integer.MAX_VALUE, Math.max(1L, pendingDeferredFlushes / 8L)),
            pendingDeferredFlushes
        );
        if (fanoutKernel.decision() == AGCNetworkFanoutKernel.Decision.ORDERED_NOW) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, fanoutKernel.reason());
            return this.lastNetworkDecision;
        }
        final AGCFanoutDeduplicator.Decision fanoutDecision = AGCFanoutDeduplicator.INSTANCE.admit(
            AGCFanoutDeduplicator.Channel.COSMETIC_PACKET,
            pendingDeferredFlushes / 64L,
            packetName,
            networkCost
        );
        if (!fanoutDecision.admitted()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, "fanout budget preserved " + packetName);
            return this.lastNetworkDecision;
        }
        final AGCStorageIoGovernor.Admission compressionAdmission = AGCStorageIoGovernor.INSTANCE.admit(
            AGCStorageIoGovernor.Operation.PACKET_COMPRESSION,
            networkCost,
            packetName
        );
        if (!compressionAdmission.admitted()) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, "compression helper budget preserved " + packetName);
            return this.lastNetworkDecision;
        }
        final AGCResourceEfficiencyEngine.Grant survivalGrant = AGCSurvivalScaleCoordinator.INSTANCE.claimInvisibleWork(
            AGCResourceEfficiencyEngine.Domain.NETWORK,
            null,
            networkCost,
            packetName
        );
        final AGCTickBudgetArbiter.Admission budget = survivalGrant.admitted() ? AGCTickBudgetArbiter.INSTANCE.claim(
            AGCTickBudgetArbiter.Category.NETWORK_DEADLINE,
            networkCost,
            false
        ) : new AGCTickBudgetArbiter.Admission(false, survivalGrant.remainingUnits(), survivalGrant.reason());
        if (!budget.admitted() || !AGCPerformanceStandard.INSTANCE.allowsDeadlineTicks(this.maxNetworkDeadlineTicks)) {
            this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.IMMEDIATE_ORDERED, "network deadline budget preserved " + packetName);
            return this.lastNetworkDecision;
        }
        AGCLatencySLO.INSTANCE.recordNetworkBatch(this.maxNetworkDeadlineTicks);
        this.lastNetworkDecision = this.record(Lane.NETWORK_DEADLINE_BATCH, Decision.DEADLINE_ORDERED_BATCH, packetName);
        return this.lastNetworkDecision;
    }

    public Decision admitChunkQueue(final java.util.UUID playerId, final AGCChunkBudget.Operation operation) {
        this.requests.get(Lane.CHUNK_FIFO_FAIR_QUEUE).incrementAndGet();
        final AGCTickBudgetArbiter.Category category;
        if (operation == AGCChunkBudget.Operation.GENERATE) {
            category = AGCTickBudgetArbiter.Category.CHUNK_GENERATE;
        } else if (operation == AGCChunkBudget.Operation.SEND) {
            category = AGCTickBudgetArbiter.Category.CHUNK_SEND;
        } else {
            category = AGCTickBudgetArbiter.Category.CHUNK_LOAD;
        }
        final AGCPlayerClusterMovementPlanner.Cluster chunkCluster22 = AGCPlayerClusterMovementPlanner.INSTANCE.plan(playerId, 32, operation == null ? "chunk" : operation.name());
        final AGCChunkTerrainDemandModel.Forecast terrainForecast = AGCChunkTerrainDemandModel.INSTANCE.forecast(
            playerId,
            operation,
            operation == AGCChunkBudget.Operation.GENERATE ? 24 : operation == AGCChunkBudget.Operation.LOAD ? 18 : 12,
            chunkCluster22.clusterSize(),
            operation == null ? "chunk" : operation.name()
        );
        if (!terrainForecast.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, terrainForecast.reason());
            return this.lastChunkDecision;
        }
        final AGCChunkFrontierScheduler.Frontier frontier = AGCChunkFrontierScheduler.INSTANCE.schedule(
            playerId,
            operation,
            operation == AGCChunkBudget.Operation.GENERATE ? 24 : operation == AGCChunkBudget.Operation.LOAD ? 18 : 12,
            chunkCluster22.clusterSize(),
            terrainForecast.reason()
        );
        if (!frontier.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, frontier.reason());
            return this.lastChunkDecision;
        }
        final AGCChunkHorizonCompiler.Horizon chunkHorizon = AGCChunkHorizonCompiler.INSTANCE.compile(
            playerId,
            operation,
            frontier.rings(),
            chunkCluster22.clusterSize(),
            frontier.reason()
        );
        if (!chunkHorizon.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkHorizon.reason());
            return this.lastChunkDecision;
        }
        final AGCScale24HotPathRuntime.Grant chunkHotPath = AGCScale24HotPathRuntime.INSTANCE.claim(
            AGCScale24HotPathRuntime.Domain.CHUNK_HORIZON,
            chunkHorizon.cost(),
            false,
            chunkHorizon.reason()
        );
        if (!chunkHotPath.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkHotPath.reason());
            return this.lastChunkDecision;
        }
        final AGCHotPathPlanCache.Plan cachedChunkPlan = AGCHotPathPlanCache.INSTANCE.compile(
            AGCHotPathPlanCache.Kind.CHUNK_HORIZON,
            chunkHorizon.stableKey(),
            chunkHorizon.cost(),
            false,
            chunkHorizon.reason()
        );
        if (!cachedChunkPlan.compiled()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, cachedChunkPlan.reason());
            return this.lastChunkDecision;
        }
        final AGCScale23DispatchTable.Dispatch chunkDispatch = AGCScale23DispatchTable.INSTANCE.compile(
            AGCScale23DispatchTable.Lane.CHUNK_FRONTIER,
            frontier.cost(),
            false,
            frontier.reason()
        );
        if (!chunkDispatch.compiled()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkDispatch.reason());
            return this.lastChunkDecision;
        }
        final AGCWorkerRoutePlanner.Route chunkRoute = AGCWorkerRoutePlanner.INSTANCE.route("chunk-frontier:" + (operation == null ? "chunk" : operation.name()), frontier.cost(), false);
        if (!chunkRoute.routed()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkRoute.reason());
            return this.lastChunkDecision;
        }
        final AGCScale22TickWorkCompiler.Compile compiledChunk = AGCScale22TickWorkCompiler.INSTANCE.compile(
            AGCScale22TickWorkCompiler.Track.CHUNK_DEMAND,
            Math.max(1L, terrainForecast.stripes()),
            false,
            terrainForecast.reason()
        );
        if (!compiledChunk.compiled()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, compiledChunk.reason());
            return this.lastChunkDecision;
        }
        final AGCSystemLoadShepherd.Permit chunkLoad = AGCSystemLoadShepherd.INSTANCE.permit("chunk", Math.max(1L, terrainForecast.stripes()), false);
        if (!chunkLoad.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkLoad.reason());
            return this.lastChunkDecision;
        }
        AGCSemanticHotPathMeter.INSTANCE.record(
            AGCSemanticHotPathMeter.Kind.CHUNK_FORECAST,
            Math.max(1L, terrainForecast.demand()),
            Math.max(1L, terrainForecast.stripes()),
            operation == null ? "chunk" : operation.name()
        );
        final AGCPlayerMotionIntentModel.Intent motionIntent = AGCPlayerMotionIntentModel.INSTANCE.planStatic(playerId, operation == null ? "chunk" : operation.name());
        final AGCPlayerClusterMovementPlanner.Cluster movementCluster = AGCPlayerClusterMovementPlanner.INSTANCE.plan(playerId, operation == null ? 24 : 48, operation == null ? "chunk" : operation.name());
        final AGCScale21CriticalPathRuntime.Grant criticalChunk = AGCScale21CriticalPathRuntime.INSTANCE.claim(
            AGCScale21CriticalPathRuntime.Domain.CHUNK_STORM,
            Math.max(1L, movementCluster.clusterSize() * movementCluster.lookaheadBias()),
            false,
            operation == null ? "chunk" : operation.name()
        );
        if (!criticalChunk.admitted() && movementCluster.clusterSize() > 96) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, criticalChunk.reason());
            return this.lastChunkDecision;
        }
        final AGCWorldLogicPhaseDAG.Decision chunkPhase = AGCWorldLogicPhaseDAG.INSTANCE.classify(AGCWorldLogicPhaseDAG.Phase.CHUNK_INTENT_PLAN, operation == null ? "chunk" : operation.name());
        if (chunkPhase.orderedBarrier()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkPhase.reason());
            return this.lastChunkDecision;
        }
        final AGCChunkLookaheadPlanner.Admission lookahead = AGCChunkLookaheadPlanner.INSTANCE.plan(playerId, operation, motionIntent, 10, operation == null ? "chunk" : operation.name());
        if (!lookahead.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, lookahead.reason());
            return this.lastChunkDecision;
        }
        final AGCChunkDemandForecastTable.Forecast demandForecast = AGCChunkDemandForecastTable.INSTANCE.forecast(
            playerId,
            operation,
            motionIntent,
            lookahead.radius(),
            0L,
            operation == null ? "chunk" : operation.name()
        );
        if (!demandForecast.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, demandForecast.reason());
            return this.lastChunkDecision;
        }
        final AGCChunkStormController.Plan stormPlan = AGCChunkStormController.INSTANCE.plan(
            playerId,
            operation,
            demandForecast.radius(),
            movementCluster.clusterSize(),
            demandForecast.reason()
        );
        if (!stormPlan.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, stormPlan.reason());
            return this.lastChunkDecision;
        }
        AGCComputeWasteReducer.INSTANCE.record(
            AGCComputeWasteReducer.Kind.CHUNK_INTENT,
            Math.max(1L, demandForecast.hints()),
            Math.max(1L, stormPlan.sliceWidth()),
            operation == null ? "chunk" : operation.name()
        );
        final AGCChunkWorksetSlicer.Workset chunkWorkset = AGCChunkWorksetSlicer.INSTANCE.slice(
            playerId,
            operation,
            demandForecast.radius(),
            0L,
            operation == null ? "chunk" : operation.name()
        );
        if (!chunkWorkset.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkWorkset.reason());
            return this.lastChunkDecision;
        }
        final AGCResourcePlacementPlanner.Placement chunkPlacement = AGCResourcePlacementPlanner.INSTANCE.place("chunk:" + (operation == null ? "unknown" : operation.name()), Math.max(1L, chunkWorkset.slices()), false);
        if (!chunkPlacement.placed()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkPlacement.reason());
            return this.lastChunkDecision;
        }
        final AGCAdaptiveLocalityRing.Admission chunkRing = AGCAdaptiveLocalityRing.INSTANCE.claim(
            "chunk-forecast:" + Long.toUnsignedString(demandForecast.stableKey()),
            Math.max(4096L, demandForecast.hints() * 48L)
        );
        if (!chunkRing.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkRing.reason());
            return this.lastChunkDecision;
        }
        final AGCChunkPredictiveIndex.Plan predictiveIndex = AGCChunkPredictiveIndex.INSTANCE.plan(playerId, operation, motionIntent, demandForecast.radius(), 0);
        if (!predictiveIndex.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, predictiveIndex.reason());
            return this.lastChunkDecision;
        }
        final AGCChunkSpatialRoutePlanner.Plan routePlan = AGCChunkSpatialRoutePlanner.INSTANCE.plan(playerId, operation, 0, 0, predictiveIndex.effectiveRadius(), predictiveIndex.reason());
        if (!routePlan.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, routePlan.reason());
            return this.lastChunkDecision;
        }
        final AGCCacheLocalityWindow.Admission chunkLocality = AGCCacheLocalityWindow.INSTANCE.claim(
            "chunk-predictive:" + (playerId == null ? "unknown" : playerId),
            Math.max(4096L, predictiveIndex.hints() * 32L)
        );
        if (!chunkLocality.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkLocality.reason());
            return this.lastChunkDecision;
        }
        final AGCChunkIntentCompactor.Admission compactedIntent = AGCChunkIntentCompactor.INSTANCE.plan(playerId, operation, "unknown", 0, 0);
        if (!compactedIntent.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, compactedIntent.reason());
            return this.lastChunkDecision;
        }
        final AGCMemoryLocalityPlanner.Admission chunkScratch = AGCMemoryLocalityPlanner.INSTANCE.claimScratch("chunk:" + (operation == null ? "unknown" : operation.name()), 4096L, "chunk intent compaction scratch");
        if (!chunkScratch.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, chunkScratch.reason());
            return this.lastChunkDecision;
        }
        final AGCChunkPipelineKernel.Admission pipelineAdmission = AGCChunkPipelineKernel.INSTANCE.admit(playerId, operation, "unknown", 0, 0);
        if (!pipelineAdmission.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, pipelineAdmission.reason());
            return this.lastChunkDecision;
        }
        final AGCChunkIntentPlanner.Admission intentAdmission = AGCChunkIntentPlanner.INSTANCE.plan(playerId, 0, 0, operation);
        if (!intentAdmission.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, intentAdmission.reason());
            return this.lastChunkDecision;
        }
        final AGCStorageIoGovernor.Admission ioAdmission = AGCStorageIoGovernor.INSTANCE.admit(
            operation == AGCChunkBudget.Operation.SEND ? AGCStorageIoGovernor.Operation.CHUNK_WRITE_HINT : AGCStorageIoGovernor.Operation.CHUNK_READ_HINT,
            1L,
            operation == null ? "chunk" : operation.name()
        );
        if (!ioAdmission.admitted()) {
            this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, Decision.FIFO_FAIR_WAIT, ioAdmission.reason());
            return this.lastChunkDecision;
        }
        final AGCResourceEfficiencyEngine.Grant survivalGrant = AGCSurvivalScaleCoordinator.INSTANCE.claimInvisibleWork(
            AGCResourceEfficiencyEngine.Domain.CHUNK,
            playerId,
            1L,
            operation == null ? "chunk" : operation.name()
        );
        final AGCTickBudgetArbiter.Admission budget = survivalGrant.admitted()
            ? AGCTickBudgetArbiter.INSTANCE.claim(category, 1L, false)
            : new AGCTickBudgetArbiter.Admission(false, survivalGrant.remainingUnits(), survivalGrant.reason());
        final AGCChunkFairQueue.Admission admission = budget.admitted() ? AGCChunkFairQueue.INSTANCE.admit(playerId, operation) : new AGCChunkFairQueue.Admission(false, 0, budget.reason());
        final Decision decision = admission.admitted() ? Decision.ORDERED_COMMIT : Decision.FIFO_FAIR_WAIT;
        this.lastChunkDecision = this.record(Lane.CHUNK_FIFO_FAIR_QUEUE, decision, operation == null ? "chunk" : operation.name());
        return this.lastChunkDecision;
    }

    public Decision admitEntitySnapshot(final java.util.UUID playerId, final int estimatedEntities, final String reason) {
        this.requests.get(Lane.ENTITY_READ_ONLY_SNAPSHOT).incrementAndGet();
        final AGCEntityWitnessIndex.WitnessPlan witnessPlan = AGCEntityWitnessIndex.INSTANCE.index(
            playerId,
            estimatedEntities,
            Math.max(1, estimatedEntities / 64),
            reason
        );
        if (!witnessPlan.admitted() && estimatedEntities > 4096) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.ORDERED_COMMIT, witnessPlan.reason());
            return this.lastEntityDecision;
        }
        final AGCResourcePlacementPlanner.Placement entityPlacement = AGCResourcePlacementPlanner.INSTANCE.place("entity-witness:" + (reason == null ? "entity" : reason), Math.max(1L, witnessPlan.witnesses() / 16L), false);
        if (!entityPlacement.placed() && estimatedEntities > 4096) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.ORDERED_COMMIT, entityPlacement.reason());
            return this.lastEntityDecision;
        }
        if (!this.enabled) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, reason);
            return this.lastEntityDecision;
        }
        final AGCEntityObserverMatrix.Matrix observerMatrix = AGCEntityObserverMatrix.INSTANCE.plan(
            playerId,
            estimatedEntities,
            Math.max(1, estimatedEntities / 128),
            reason
        );
        if (!observerMatrix.admitted() && estimatedEntities > 8192) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, observerMatrix.reason());
            return this.lastEntityDecision;
        }
        final AGCEntityVisibilityLattice.Lattice visibilityLattice = AGCEntityVisibilityLattice.INSTANCE.prepare(
            playerId,
            estimatedEntities,
            Math.max(1, estimatedEntities / 128),
            reason
        );
        if (!visibilityLattice.prepared() && estimatedEntities > 8192) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, visibilityLattice.reason());
            return this.lastEntityDecision;
        }
        final AGCEntityObserverSetCompiler.ObserverSet observerSet24 = AGCEntityObserverSetCompiler.INSTANCE.prepare(
            playerId,
            estimatedEntities,
            Math.max(1, saturatingInt(visibilityLattice.latticeNodes() / 256L)),
            visibilityLattice.reason()
        );
        if (!observerSet24.prepared() && estimatedEntities > 12_288) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, observerSet24.reason());
            return this.lastEntityDecision;
        }
        final AGCScale24HotPathRuntime.Grant entityHotPath = AGCScale24HotPathRuntime.INSTANCE.claim(
            AGCScale24HotPathRuntime.Domain.ENTITY_OBSERVER_SET,
            observerSet24.cost(),
            false,
            observerSet24.reason()
        );
        if (!entityHotPath.admitted() && estimatedEntities > 16_384) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, entityHotPath.reason());
            return this.lastEntityDecision;
        }
        final AGCHotPathPlanCache.Plan cachedEntityPlan = AGCHotPathPlanCache.INSTANCE.compile(
            AGCHotPathPlanCache.Kind.ENTITY_OBSERVER_SET,
            observerSet24.stableKey(),
            observerSet24.cost(),
            false,
            observerSet24.reason()
        );
        if (!cachedEntityPlan.compiled() && estimatedEntities > 16_384) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, cachedEntityPlan.reason());
            return this.lastEntityDecision;
        }
        final AGCScale23DispatchTable.Dispatch entityDispatch = AGCScale23DispatchTable.INSTANCE.compile(
            AGCScale23DispatchTable.Lane.ENTITY_VISIBILITY_LATTICE,
            Math.max(1L, visibilityLattice.latticeNodes() / 16L),
            false,
            visibilityLattice.reason()
        );
        if (!entityDispatch.compiled() && estimatedEntities > 12288) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, entityDispatch.reason());
            return this.lastEntityDecision;
        }
        final AGCWorkerRoutePlanner.Route entityRoute = AGCWorkerRoutePlanner.INSTANCE.route("entity-lattice:" + (reason == null ? "entity" : reason), Math.max(1L, visibilityLattice.latticeNodes() / 32L), false);
        if (!entityRoute.routed() && estimatedEntities > 16384) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, entityRoute.reason());
            return this.lastEntityDecision;
        }
        final AGCScale22TickWorkCompiler.Compile compiledEntity = AGCScale22TickWorkCompiler.INSTANCE.compile(
            AGCScale22TickWorkCompiler.Track.ENTITY_OBSERVER,
            Math.max(1L, observerMatrix.reducedPairs() / 32L),
            false,
            observerMatrix.reason()
        );
        if (!compiledEntity.compiled() && estimatedEntities > 12_288) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, compiledEntity.reason());
            return this.lastEntityDecision;
        }
        AGCSemanticHotPathMeter.INSTANCE.record(
            AGCSemanticHotPathMeter.Kind.ENTITY_OBSERVER,
            Math.max(1L, observerMatrix.rawPairs()),
            Math.max(1L, observerMatrix.reducedPairs()),
            reason
        );
        final AGCPlayerClusterMovementPlanner.Cluster entityCluster = AGCPlayerClusterMovementPlanner.INSTANCE.plan(playerId, Math.max(1, estimatedEntities / 128), reason);
        final AGCEntityDensityField.Field densityField = AGCEntityDensityField.INSTANCE.prepare(playerId, estimatedEntities, entityCluster.clusterSize(), reason);
        final AGCScale21CriticalPathRuntime.Grant criticalEntity = AGCScale21CriticalPathRuntime.INSTANCE.claim(
            AGCScale21CriticalPathRuntime.Domain.ENTITY_DENSITY,
            Math.max(1L, densityField.candidateBudget() / 16L),
            false,
            reason
        );
        if (!criticalEntity.admitted() && densityField.band() == AGCEntityDensityField.DensityBand.EXTREME) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, criticalEntity.reason());
            return this.lastEntityDecision;
        }
        AGCComputeWasteReducer.INSTANCE.record(
            AGCComputeWasteReducer.Kind.ENTITY_CANDIDATE,
            Math.max(1L, estimatedEntities),
            Math.max(1L, densityField.candidateBudget()),
            reason
        );
        final long entityCost = Math.max(1L, estimatedEntities);
        final AGCWorldLogicPhaseDAG.Decision entityPhase = AGCWorldLogicPhaseDAG.INSTANCE.classify(AGCWorldLogicPhaseDAG.Phase.ENTITY_CANDIDATE_PLAN, reason);
        if (entityPhase.orderedBarrier()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, entityPhase.reason());
            return this.lastEntityDecision;
        }
        final AGCPlayerMotionIntentModel.Intent entityIntent = AGCPlayerMotionIntentModel.INSTANCE.planStatic(playerId, reason);
        final AGCEntitySpatialBandIndex.Admission spatialBand = AGCEntitySpatialBandIndex.INSTANCE.prepare(playerId, estimatedEntities, entityIntent.worldKey());
        if (!spatialBand.admitted()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, spatialBand.reason());
            return this.lastEntityDecision;
        }
        final AGCEntityInterestSetReducer.Reduction interestReduction = AGCEntityInterestSetReducer.INSTANCE.reduce(
            playerId,
            estimatedEntities,
            Math.max(1, spatialBand.band().ordinal() + 1),
            reason
        );
        if (!interestReduction.admitted()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, interestReduction.reason());
            return this.lastEntityDecision;
        }
        final AGCAdaptiveLocalityRing.Admission entityRing = AGCAdaptiveLocalityRing.INSTANCE.claim(
            "entity-interest:" + (playerId == null ? "unknown" : playerId),
            Math.max(2048L, interestReduction.reducedCandidates() * 24L)
        );
        if (!entityRing.admitted()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, entityRing.reason());
            return this.lastEntityDecision;
        }
        final AGCEntityInteractionGraph.Admission interactionGraph = AGCEntityInteractionGraph.INSTANCE.prepare(playerId, interestReduction.reducedCandidates(), entityIntent.worldKey());
        if (!interactionGraph.admitted()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, interactionGraph.reason());
            return this.lastEntityDecision;
        }
        final AGCEntityTrackerDeltaIndex.Admission deltaIndex = AGCEntityTrackerDeltaIndex.INSTANCE.prepare(
            playerId,
            estimatedEntities,
            Math.max(1, estimatedEntities / 8),
            entityIntent.worldKey()
        );
        if (!deltaIndex.prepared()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, deltaIndex.reason());
            return this.lastEntityDecision;
        }
        final AGCCacheLocalityWindow.Admission entityLocality = AGCCacheLocalityWindow.INSTANCE.claim(
            "entity-graph:" + (playerId == null ? "unknown" : playerId),
            Math.max(4096L, interactionGraph.candidateEdges() * 8L)
        );
        if (!entityLocality.admitted()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, entityLocality.reason());
            return this.lastEntityDecision;
        }
        final AGCEntityBandingPlanner.Decision bandingDecision = AGCEntityBandingPlanner.INSTANCE.prepare(playerId, estimatedEntities, reason);
        if (!bandingDecision.prepared()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, bandingDecision.reason());
            return this.lastEntityDecision;
        }
        final AGCMemoryLocalityPlanner.Admission entityScratch = AGCMemoryLocalityPlanner.INSTANCE.claimScratch("entity:" + (playerId == null ? "unknown" : playerId), Math.max(2048L, (long) Math.max(1, estimatedEntities) * 16L), reason);
        if (!entityScratch.admitted()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, entityScratch.reason());
            return this.lastEntityDecision;
        }
        final AGCEntityTrackerKernel.Admission trackerKernel = AGCEntityTrackerKernel.INSTANCE.prepare(playerId, estimatedEntities, reason);
        if (!trackerKernel.prepared()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, trackerKernel.reason());
            return this.lastEntityDecision;
        }
        final AGCEntityVisibilityPreselector.Decision visibilityDecision = AGCEntityVisibilityPreselector.INSTANCE.prepare(playerId, estimatedEntities, reason);
        if (!visibilityDecision.prepared()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, visibilityDecision.reason());
            return this.lastEntityDecision;
        }
        final AGCFanoutDeduplicator.Decision entityFanout = AGCFanoutDeduplicator.INSTANCE.admit(
            AGCFanoutDeduplicator.Channel.ENTITY_TRACKER,
            playerId == null ? 0L : playerId.getMostSignificantBits(),
            reason,
            entityCost
        );
        if (!entityFanout.admitted()) {
            this.lastEntityDecision = this.record(Lane.ENTITY_READ_ONLY_SNAPSHOT, Decision.IMMEDIATE_ORDERED, entityFanout.reason());
            return this.lastEntityDecision;
        }
        final AGCResourceEfficiencyEngine.Grant survivalGrant = AGCSurvivalScaleCoordinator.INSTANCE.claimInvisibleWork(
            AGCResourceEfficiencyEngine.Domain.ENTITY,
            playerId,
            entityCost,
            reason
        );
        final AGCTickBudgetArbiter.Admission budget = survivalGrant.admitted() ? AGCTickBudgetArbiter.INSTANCE.claim(
            AGCTickBudgetArbiter.Category.ENTITY_SNAPSHOT,
            entityCost,
            false
        ) : new AGCTickBudgetArbiter.Admission(false, survivalGrant.remainingUnits(), survivalGrant.reason());
        final boolean admitted = budget.admitted() && AGCEntitySnapshotPlanner.INSTANCE.admit(playerId, estimatedEntities, reason).admitted();
        this.lastEntityDecision = this.record(
            Lane.ENTITY_READ_ONLY_SNAPSHOT,
            admitted ? Decision.READ_ONLY_PREPARE : Decision.IMMEDIATE_ORDERED,
            reason
        );
        return this.lastEntityDecision;
    }

    public Decision admitWorldPlan(final AGCWorldWriteIntentGraph.Plan plan, final long translatorPending) {
        this.requests.get(Lane.WORLD_WRITE_INTENT_WAVE).incrementAndGet();
        if (!this.enabled || plan == null || translatorPending >= this.maxTranslatorPending) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, "translator pressure");
            return this.lastWorldDecision;
        }
        final AGCMultiversePhaseMatrix.Matrix phaseMatrix = AGCMultiversePhaseMatrix.INSTANCE.compile(plan, translatorPending);
        if (!phaseMatrix.readOnlyPrepare()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, phaseMatrix.reason());
            return this.lastWorldDecision;
        }
        final AGCWorldBarrierDAGCompiler.Plan barrierDag = AGCWorldBarrierDAGCompiler.INSTANCE.compile(plan, translatorPending);
        if (!barrierDag.readOnlyPrepare()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, barrierDag.reason());
            return this.lastWorldDecision;
        }
        final AGCWorldReadOnlyBatchGraph.Graph worldBatch24 = AGCWorldReadOnlyBatchGraph.INSTANCE.compile(plan, translatorPending);
        if (!worldBatch24.readOnlyPrepare()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, worldBatch24.reason());
            return this.lastWorldDecision;
        }
        final AGCScale24HotPathRuntime.Grant worldHotPath = AGCScale24HotPathRuntime.INSTANCE.claim(
            AGCScale24HotPathRuntime.Domain.WORLD_READ_ONLY_BATCH,
            worldBatch24.cost(),
            false,
            worldBatch24.reason()
        );
        if (!worldHotPath.admitted()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, worldHotPath.reason());
            return this.lastWorldDecision;
        }
        final AGCHotPathPlanCache.Plan cachedWorldPlan = AGCHotPathPlanCache.INSTANCE.compile(
            AGCHotPathPlanCache.Kind.WORLD_BATCH_GRAPH,
            worldBatch24.stableKey(),
            worldBatch24.cost(),
            false,
            worldBatch24.reason()
        );
        if (!cachedWorldPlan.compiled()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, cachedWorldPlan.reason());
            return this.lastWorldDecision;
        }
        final AGCScale23DispatchTable.Dispatch worldDispatch = AGCScale23DispatchTable.INSTANCE.compile(
            AGCScale23DispatchTable.Lane.WORLD_BARRIER_DAG,
            barrierDag.cost(),
            false,
            barrierDag.reason()
        );
        if (!worldDispatch.compiled()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, worldDispatch.reason());
            return this.lastWorldDecision;
        }
        final AGCWorkerRoutePlanner.Route worldRoute = AGCWorkerRoutePlanner.INSTANCE.route("world-barrier-dag", barrierDag.cost(), false);
        if (!worldRoute.routed()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, worldRoute.reason());
            return this.lastWorldDecision;
        }
        final AGCScale22TickWorkCompiler.Compile compiledWorld = AGCScale22TickWorkCompiler.INSTANCE.compile(
            AGCScale22TickWorkCompiler.Track.WORLD_PHASE,
            phaseMatrix.cost(),
            false,
            phaseMatrix.reason()
        );
        if (!compiledWorld.compiled()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, compiledWorld.reason());
            return this.lastWorldDecision;
        }
        final AGCPluginDeterministicTicketCache.Ticket pluginTicket24 = AGCPluginDeterministicTicketCache.INSTANCE.classify(
            "world",
            "pure-compute-world-phase-plan",
            true,
            phaseMatrix.cost()
        );
        if (pluginTicket24.orderedCommit() || pluginTicket24.forbiddenMutation()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, pluginTicket24.reason());
            return this.lastWorldDecision;
        }
        final AGCHotPathPlanCache.Plan cachedPluginTicket = AGCHotPathPlanCache.INSTANCE.compile(
            AGCHotPathPlanCache.Kind.PLUGIN_TICKET,
            pluginTicket24.stableKey(),
            Math.max(1L, pluginTicket24.tickets()),
            false,
            pluginTicket24.reason()
        );
        if (!cachedPluginTicket.compiled()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, cachedPluginTicket.reason());
            return this.lastWorldDecision;
        }
        final AGCPluginCommitCoalescer.Entry coalescedCommit = AGCPluginCommitCoalescer.INSTANCE.classify(
            "world",
            "pure-compute-world-phase-plan",
            true,
            phaseMatrix.cost()
        );
        if (coalescedCommit.orderedCommit() || coalescedCommit.forbiddenMutation()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, coalescedCommit.reason());
            return this.lastWorldDecision;
        }
        final AGCScale23DispatchTable.Dispatch pluginDispatch = AGCScale23DispatchTable.INSTANCE.compile(
            AGCScale23DispatchTable.Lane.PLUGIN_COMMIT_COALESCE,
            Math.max(1L, coalescedCommit.coalescedTickets()),
            false,
            coalescedCommit.reason()
        );
        if (!pluginDispatch.compiled()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, pluginDispatch.reason());
            return this.lastWorldDecision;
        }
        final AGCPluginOrderedCommitLedger.Entry pluginLedger = AGCPluginOrderedCommitLedger.INSTANCE.classify(
            "world",
            "pure-compute-world-phase-plan",
            true,
            phaseMatrix.cost()
        );
        if (pluginLedger.orderedCommit() || pluginLedger.forbiddenMutation()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, pluginLedger.reason());
            return this.lastWorldDecision;
        }
        AGCSemanticHotPathMeter.INSTANCE.record(
            AGCSemanticHotPathMeter.Kind.WORLD_PHASE,
            Math.max(1L, phaseMatrix.worlds() * Math.max(1, phaseMatrix.worlds())),
            Math.max(1L, phaseMatrix.waves() + phaseMatrix.barriers()),
            phaseMatrix.reason()
        );
        final AGCMultiverseLaneAllocator.Allocation laneAllocation = AGCMultiverseLaneAllocator.INSTANCE.allocate(
            Math.max(1, plan.nodes().size()),
            Math.max(0, plan.parallelGroups()),
            translatorPending
        );
        if (!laneAllocation.readOnlyPrepare()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, laneAllocation.reason());
            return this.lastWorldDecision;
        }
        final AGCPluginCommitSequencer.Decision sequencerDecision = AGCPluginCommitSequencer.INSTANCE.classify(
            "world",
            "pure-compute-world-phase-plan",
            true,
            false,
            Math.max(1L, laneAllocation.lanes())
        );
        if (sequencerDecision.orderedCommit()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, sequencerDecision.reason());
            return this.lastWorldDecision;
        }
        final AGCScale21CriticalPathRuntime.Grant criticalWorld = AGCScale21CriticalPathRuntime.INSTANCE.claim(
            AGCScale21CriticalPathRuntime.Domain.WORLD_LANE,
            Math.max(1L, laneAllocation.lanes() * 8L),
            false,
            laneAllocation.reason()
        );
        if (!criticalWorld.admitted()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, criticalWorld.reason());
            return this.lastWorldDecision;
        }
        AGCComputeWasteReducer.INSTANCE.record(AGCComputeWasteReducer.Kind.WORLD_PLAN, Math.max(1L, plan.nodes().size() * Math.max(1, plan.nodes().size())), Math.max(1L, laneAllocation.lanes()), plan.mode().name());
        final AGCWorldSemanticScheduler.Schedule semanticSchedule = AGCWorldSemanticScheduler.INSTANCE.compile(plan, translatorPending);
        if (!semanticSchedule.prepareConcurrent()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, semanticSchedule.reason());
            return this.lastWorldDecision;
        }
        final AGCPluginDeterminismGuard.Decision determinism = AGCPluginDeterminismGuard.INSTANCE.classify("world-planner", "pure-compute-world-phase-plan", true, true);
        if (determinism.orderedCommit() || determinism.forbiddenMutation()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, determinism.reason());
            return this.lastWorldDecision;
        }
        final AGCResourcePlacementPlanner.Placement worldPlacement = AGCResourcePlacementPlanner.INSTANCE.place("world-plan", Math.max(1L, semanticSchedule.prepareSlots() * 4L), false);
        if (!worldPlacement.placed()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, worldPlacement.reason());
            return this.lastWorldDecision;
        }
        final long worldCost = Math.max(1L, plan.parallelGroups());
        final AGCWorldPhaseBatchCompiler.Batch phaseBatch = AGCWorldPhaseBatchCompiler.INSTANCE.compile(plan, translatorPending);
        if (!phaseBatch.readOnlyBatches()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, phaseBatch.reason());
            return this.lastWorldDecision;
        }
        final AGCPluginSemanticJit.Decision semanticJit = AGCPluginSemanticJit.INSTANCE.classify(
            "world",
            "pure-compute-world-phase-plan",
            true,
            worldCost
        );
        if (semanticJit.orderedCommit() || semanticJit.forbiddenMutation()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, semanticJit.reason());
            return this.lastWorldDecision;
        }
        final AGCAdaptiveLocalityRing.Admission worldRing = AGCAdaptiveLocalityRing.INSTANCE.claim(
            "world-phase-batch:" + plan.mode(),
            Math.max(4096L, phaseBatch.batches() * 2048L)
        );
        if (!worldRing.admitted()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, worldRing.reason());
            return this.lastWorldDecision;
        }
        final AGCUnifiedTickPlanCompiler.Admission unifiedAdmission = AGCUnifiedTickPlanCompiler.INSTANCE.admit(
            AGCUnifiedTickPlanCompiler.Lane.WORLD_PHASE,
            worldCost,
            false,
            plan.mode().name()
        );
        if (!unifiedAdmission.readOnlyPrepare()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, unifiedAdmission.reason());
            return this.lastWorldDecision;
        }
        final AGCPluginSemanticFirewall.Decision firewallDecision = AGCPluginSemanticFirewall.INSTANCE.classify(
            "world-planner",
            "pure-compute-world-phase-plan",
            true,
            worldCost
        );
        if (firewallDecision.orderedCommit() || firewallDecision.forbiddenMutation()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, firewallDecision.reason());
            return this.lastWorldDecision;
        }
        final AGCWorldPhaseExecutorPlan.Plan executorPlan = AGCWorldPhaseExecutorPlan.INSTANCE.compile(plan, translatorPending);
        if (!executorPlan.prepareConcurrent()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, executorPlan.reason());
            return this.lastWorldDecision;
        }
        final AGCPluginContractVerifier.Decision contractDecision = AGCPluginContractVerifier.INSTANCE.verify(
            "world",
            "pure-compute-world-phase-plan",
            worldCost
        );
        if (contractDecision.orderedCommit() || contractDecision.forbiddenMutation()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, contractDecision.reason());
            return this.lastWorldDecision;
        }
        final AGCWorldLogicPhaseDAG.Decision logicPhase = AGCWorldLogicPhaseDAG.INSTANCE.worldPlan(plan, translatorPending);
        if (logicPhase.orderedBarrier()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, logicPhase.reason());
            return this.lastWorldDecision;
        }
        final AGCPluginSemanticModel.Decision pluginSemantic = AGCPluginSemanticModel.INSTANCE.classify("world", plan.mode().name());
        if (pluginSemantic.orderedCommit()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, pluginSemantic.reason());
            return this.lastWorldDecision;
        }
        final AGCPluginBackpressureBridge.Decision pluginRoute = AGCPluginBackpressureBridge.INSTANCE.route(AGCPluginBackpressureBridge.Operation.PURE_COMPUTE, worldCost, "world-planner");
        if (!pluginRoute.admitted()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, pluginRoute.reason());
            return this.lastWorldDecision;
        }
        final AGCThreadAffinityTranslator.Translation translation = AGCThreadAffinityTranslator.INSTANCE.translate(
            AGCThreadAffinityTranslator.Phase.PURE_COMPUTE_PLAN,
            "world-planner",
            plan.mode().name()
        );
        if (translation.phase() == AGCThreadAffinityTranslator.Phase.ORDERED_PRIMARY_COMMIT) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, translation.reason());
            return this.lastWorldDecision;
        }
        final AGCScaleKernel.Admission scaleAdmission = AGCScaleKernel.INSTANCE.claim(AGCScaleKernel.Axis.WORLD_PREPARE, worldCost, plan.mode().name());
        if (!scaleAdmission.admitted()) {
            this.lastWorldDecision = this.record(Lane.WORLD_WRITE_INTENT_WAVE, Decision.ORDERED_COMMIT, scaleAdmission.reason());
            return this.lastWorldDecision;
        }
        final AGCResourceEfficiencyEngine.Grant survivalGrant = AGCSurvivalScaleCoordinator.INSTANCE.claimInvisibleWork(
            AGCResourceEfficiencyEngine.Domain.WORLD,
            null,
            worldCost,
            plan.mode().name()
        );
        final AGCTickBudgetArbiter.Admission budget = survivalGrant.admitted() ? AGCTickBudgetArbiter.INSTANCE.claim(
            AGCTickBudgetArbiter.Category.WORLD_PREPARE,
            worldCost,
            false
        ) : new AGCTickBudgetArbiter.Admission(false, survivalGrant.remainingUnits(), survivalGrant.reason());
        this.lastWorldDecision = this.record(
            Lane.WORLD_WRITE_INTENT_WAVE,
            budget.admitted() && plan.parallelGroups() > 0 ? Decision.CONFLICT_FREE_WAVE : Decision.ORDERED_COMMIT,
            plan.mode().name()
        );
        return this.lastWorldDecision;
    }

    public boolean isDeadlineBatch(final Decision decision) {
        return decision == Decision.DEADLINE_ORDERED_BATCH;
    }

    public boolean isReadOnlyPrepare(final Decision decision) {
        return decision == Decision.READ_ONLY_PREPARE;
    }

    public boolean isConflictFreeWave(final Decision decision) {
        return decision == Decision.CONFLICT_FREE_WAVE;
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.semanticStrictMode,
            this.maxNetworkDeadlineTicks,
            this.maxDeferredFlushPending,
            this.maxTranslatorPending,
            this.tickSequence,
            this.lastNetworkDecision,
            this.lastChunkDecision,
            this.lastEntityDecision,
            this.lastWorldDecision,
            copy(this.requests),
            copy(this.immediateOrdered),
            copy(this.deadlineBatched),
            copy(this.fifoWaits),
            copy(this.readOnlyPrepares),
            copy(this.conflictFreeWaves),
            copy(this.orderedCommits)
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCNoInvasionOptimizer{enabled=" + snapshot.enabled()
            + ", strict=" + snapshot.semanticStrictMode()
            + ", deadlineTicks=" + snapshot.maxNetworkDeadlineTicks()
            + ", maxDeferredFlushPending=" + snapshot.maxDeferredFlushPending()
            + ", maxTranslatorPending=" + snapshot.maxTranslatorPending()
            + ", tick=" + snapshot.tickSequence()
            + ", lastNetwork=" + snapshot.lastNetworkDecision()
            + ", lastChunk=" + snapshot.lastChunkDecision()
            + ", lastEntity=" + snapshot.lastEntityDecision()
            + ", lastWorld=" + snapshot.lastWorldDecision()
            + ", requests=" + snapshot.requests()
            + ", deadlineBatched=" + snapshot.deadlineBatched()
            + ", fifoWaits=" + snapshot.fifoWaits()
            + ", readOnlyPrepares=" + snapshot.readOnlyPrepares()
            + ", conflictFreeWaves=" + snapshot.conflictFreeWaves()
            + ", orderedCommits=" + snapshot.orderedCommits()
            + '}';
    }

    private Decision record(final Lane lane, final Decision decision, final String reason) {
        switch (decision) {
            case IMMEDIATE_ORDERED -> this.immediateOrdered.get(lane).incrementAndGet();
            case DEADLINE_ORDERED_BATCH -> this.deadlineBatched.get(lane).incrementAndGet();
            case FIFO_FAIR_WAIT -> this.fifoWaits.get(lane).incrementAndGet();
            case READ_ONLY_PREPARE -> this.readOnlyPrepares.get(lane).incrementAndGet();
            case CONFLICT_FREE_WAVE -> this.conflictFreeWaves.get(lane).incrementAndGet();
            case ORDERED_COMMIT -> this.orderedCommits.get(lane).incrementAndGet();
        }
        if (this.semanticStrictMode) {
            final AGCSemanticInvariant.Domain domain = switch (lane) {
                case NETWORK_DEADLINE_BATCH -> AGCSemanticInvariant.Domain.NETWORK;
                case CHUNK_FIFO_FAIR_QUEUE -> AGCSemanticInvariant.Domain.CHUNK;
                case ENTITY_READ_ONLY_SNAPSHOT -> AGCSemanticInvariant.Domain.ENTITY;
                case WORLD_WRITE_INTENT_WAVE -> AGCSemanticInvariant.Domain.WORLD_TICK;
                case PLAYER_FLOW -> AGCSemanticInvariant.Domain.PLAYER;
                case PLUGIN_TRANSLATION_COMMIT -> AGCSemanticInvariant.Domain.PLUGIN;
            };
            final AGCSemanticInvariant.Decision semantic = switch (decision) {
                case IMMEDIATE_ORDERED -> AGCSemanticInvariant.Decision.IMMEDIATE_ORDER_PRESERVED;
                case DEADLINE_ORDERED_BATCH -> AGCSemanticInvariant.Decision.DEADLINE_PRESERVED_BATCH;
                case FIFO_FAIR_WAIT, CONFLICT_FREE_WAVE -> AGCSemanticInvariant.Decision.CONFLICT_SERIALISED;
                case READ_ONLY_PREPARE -> AGCSemanticInvariant.Decision.READ_ONLY_PARALLEL_PREPARE;
                case ORDERED_COMMIT -> AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT;
            };
            AGCSemanticInvariant.INSTANCE.record(domain, semantic, lane + ": " + (reason == null ? "" : reason));
        }
        return decision;
    }

    private static int saturatingInt(final long value) {
        return value <= 0L ? 0 : (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static Map<Lane, Long> copy(final EnumMap<Lane, AtomicLong> source) {
        final EnumMap<Lane, Long> copy = new EnumMap<>(Lane.class);
        for (final Map.Entry<Lane, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(copy);
    }

    public record Snapshot(
        boolean enabled,
        boolean semanticStrictMode,
        int maxNetworkDeadlineTicks,
        long maxDeferredFlushPending,
        long maxTranslatorPending,
        long tickSequence,
        Decision lastNetworkDecision,
        Decision lastChunkDecision,
        Decision lastEntityDecision,
        Decision lastWorldDecision,
        Map<Lane, Long> requests,
        Map<Lane, Long> immediateOrdered,
        Map<Lane, Long> deadlineBatched,
        Map<Lane, Long> fifoWaits,
        Map<Lane, Long> readOnlyPrepares,
        Map<Lane, Long> conflictFreeWaves,
        Map<Lane, Long> orderedCommits
    ) {
    }
}
