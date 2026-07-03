package io.agcmc.agc.api;

import java.util.EnumMap;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Plugin-facing performance facade.
 * <p>
 * This class avoids direct NMS references so plugins can compile against the
 * API only. Server internals expose detailed data through /agc; plugins get a
 * stable, low-risk summary surface here.
 */
public final class AGCPerformance {
    AGCPerformance() {
    }

    public @NotNull AGCPerformanceSnapshot snapshot() {
        final EnumMap<AGCFeature, AGCFeatureStatus> states = new EnumMap<>(AGCFeature.class);
        states.put(AGCFeature.MINIGAME_API, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_THREAD_TRANSLATION, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PACKET_PRIORITY_BUDGETING, AGCFeatureStatus.UNKNOWN);
        states.put(AGCFeature.CHUNK_QUEUE_BUDGETING, AGCFeatureStatus.UNKNOWN);
        states.put(AGCFeature.PARALLEL_WORLD_TICK, AGCFeatureStatus.UNKNOWN);
        states.put(AGCFeature.OPTIMIZATION_ENVELOPE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_TRACKER_FAST_PATH, AGCFeatureStatus.UNKNOWN);
        states.put(AGCFeature.MINIGAME_ARENA_SERVICES, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SURVIVAL_SCALE_COORDINATOR, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.RESOURCE_EFFICIENCY_ENGINE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.INTEREST_GRAPH, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.GLOBAL_FAIRNESS_MATRIX, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SHARED_READ_ONLY_CACHE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.COMPUTE_TOPOLOGY_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.FANOUT_DEDUPLICATOR, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_INTENT_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_VISIBILITY_PRESELECTOR, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.STORAGE_IO_GOVERNOR, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE_KERNEL, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.REGION_HOTSPOT_MAP, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.NETWORK_FANOUT_KERNEL, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_PIPELINE_KERNEL, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_TRACKER_KERNEL, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.THREAD_AFFINITY_TRANSLATOR, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_LOGIC_TRANSLATOR, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE_CONTROL_PLANE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PACKET_SHAPE_TABLE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLAYER_COHORT_TABLE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_INTENT_COMPACTOR, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_BANDING_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.MEMORY_LOCALITY_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENCODING_REUSE_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_BACKPRESSURE_BRIDGE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.HOTSPOT_EVICTION_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE16_CONTROL_LAW, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLAYER_MOTION_INTENT_MODEL, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_LOOKAHEAD_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_SPATIAL_BAND_INDEX, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.NETWORK_DELTA_SHAPE_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.WORLD_LOGIC_PHASE_DAG, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_SEMANTIC_MODEL, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE17_ALGORITHM_KERNEL, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_PREDICTIVE_INDEX, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_INTERACTION_GRAPH, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.NETWORK_RECIPIENT_COHORT_GRAPH, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.WORLD_PHASE_EXECUTOR_PLAN, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_CONTRACT_VERIFIER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CACHE_LOCALITY_WINDOW, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE18_CONTROL_PLANE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.UNIFIED_TICK_PLAN_COMPILER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.NETWORK_SEND_GRAPH_COMPILER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_SPATIAL_ROUTE_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_TRACKER_DELTA_INDEX, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.READ_ONLY_WORK_STEALING_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_SEMANTIC_FIREWALL, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE19_LOGIC_KERNEL, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.NETWORK_MULTICAST_SHAPE_GRAPH, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_DEMAND_FORECAST_TABLE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_INTEREST_SET_REDUCER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.WORLD_PHASE_BATCH_COMPILER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_SEMANTIC_JIT, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ADAPTIVE_LOCALITY_RING, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE20_LOSSLESS_PIPELINE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.NETWORK_BACKBONE_GRAPH, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_WORKSET_SLICER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_WITNESS_INDEX, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.WORLD_SEMANTIC_SCHEDULER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_DETERMINISM_GUARD, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.RESOURCE_PLACEMENT_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE21_CRITICAL_PATH_RUNTIME, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLAYER_CLUSTER_MOVEMENT_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_STORM_CONTROLLER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_DENSITY_FIELD, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.NETWORK_BROADCAST_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_COMMIT_SEQUENCER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.MULTIVERSE_LANE_ALLOCATOR, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.COMPUTE_WASTE_REDUCER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE22_TICK_WORK_COMPILER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.NETWORK_COHORT_BACKBONE_CACHE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_TERRAIN_DEMAND_MODEL, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_OBSERVER_MATRIX, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_ORDERED_COMMIT_LEDGER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.MULTIVERSE_PHASE_MATRIX, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SYSTEM_LOAD_SHEPHERD, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SEMANTIC_HOT_PATH_METER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE23_DISPATCH_TABLE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.NETWORK_STABLE_DIFF_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_FRONTIER_SCHEDULER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_VISIBILITY_LATTICE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_COMMIT_COALESCER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.WORLD_BARRIER_DAG_COMPILER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.WORKER_ROUTE_PLANNER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.SCALE24_HOT_PATH_RUNTIME, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.HOT_PATH_PLAN_CACHE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.NETWORK_ORDER_VECTOR_COMPILER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.CHUNK_HORIZON_COMPILER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.ENTITY_OBSERVER_SET_COMPILER, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.WORLD_READ_ONLY_BATCH_GRAPH, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.PLUGIN_DETERMINISTIC_TICKET_CACHE, AGCFeatureStatus.ENABLED);
        states.put(AGCFeature.RESOURCE_LOCALITY_SCHEDULER, AGCFeatureStatus.ENABLED);
        return new AGCPerformanceSnapshot(
            "API_COMPATIBILITY_VIEW",
            0.0D,
            safeOnlineCount(),
            0L,
            0L,
            states
        );
    }

    /**
     * Provides conservative no-invasion advice for plugin-owned work. The API
     * view does not expose live server internals; it gives stable lane guidance
     * so plugins can structure heavy work as read-only prepare + ordered commit.
     */
    public @NotNull AGCPerformanceAdvice advise(final @NotNull AGCPerformanceIntent intent) {
        final AGCPerformanceAdvice.Lane lane;
        if (intent.visibleCommit()) {
            lane = AGCPerformanceAdvice.Lane.ORDERED_COMMIT;
        } else if (intent.goal() == AGCPerformanceGoal.COSMETIC_FANOUT) {
            lane = AGCPerformanceAdvice.Lane.DEADLINE_BATCH;
        } else if (intent.readOnlyPrepare()) {
            lane = AGCPerformanceAdvice.Lane.READ_ONLY_PREPARE;
        } else {
            lane = AGCPerformanceAdvice.Lane.RUN_NOW_ORDERED;
        }
        final int batch = Math.max(1, Math.min(4096, intent.estimatedCost()));
        return new AGCPerformanceAdvice(true, lane, batch, "API compatibility view: preserve Bukkit-visible order for " + intent.reason());
    }

    public @NotNull AGCPerformanceAdvice denseArenaAdvice(final int players, final int entities, final int chunkRadius) {
        final int cost = Math.max(1, players) + Math.max(0, entities / 4) + Math.max(0, chunkRadius * chunkRadius);
        return this.advise(AGCPerformanceIntent.builder(AGCPerformanceGoal.DENSE_ARENA)
            .estimatedCost(cost)
            .readOnlyPrepare(true)
            .reason("dense arena players=" + players + " entities=" + entities + " chunkRadius=" + chunkRadius)
            .build());
    }

    public @NotNull AGCResourceProfile resourceProfile(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int readOnlyBatch = Math.max(256, Math.min(16384, players * 2));
        final int commitBatch = Math.max(64, Math.min(4096, players / 2));
        return new AGCResourceProfile(
            players,
            tps,
            readOnlyBatch,
            commitBatch,
            true,
            true,
            "Use read-only prepare + ordered commit; never move Bukkit-visible mutation off the primary thread"
        );
    }

    public @NotNull AGCSurvivalScalePlan survivalScalePlan(
        final int expectedPlayers,
        final int activeWorlds,
        final int estimatedLoadedChunks,
        final int estimatedEntities
    ) {
        final int players = Math.max(1, expectedPlayers);
        final int worlds = Math.max(1, activeWorlds);
        final int shards = Math.max(worlds, Math.min(4096, (players + 63) / 64));
        final int cellSize = players >= 1000 ? 32 : 48;
        final int playersPerCell = players >= 2000 ? 48 : 24;
        return new AGCSurvivalScalePlan(
            players,
            worlds,
            Math.max(0, estimatedLoadedChunks),
            Math.max(0, estimatedEntities),
            shards,
            cellSize,
            playersPerCell,
            AGCPerformanceAdvice.Lane.DEADLINE_BATCH,
            AGCPerformanceAdvice.Lane.FIFO_WAIT,
            AGCPerformanceAdvice.Lane.READ_ONLY_PREPARE,
            "Deduplicate read-only fanout, use FIFO chunk intents, keep entity ticks and Bukkit events ordered"
        );
    }

    public @NotNull AGCComputeEfficiencyPlan computeEfficiencyPlan(final int expectedPlayers, final int targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final int tps = Math.max(1, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 2000 ? 22 : 18;
        final int readOnlyWorkers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int readOnlyBatch = Math.max(512, Math.min(65536, players * 4));
        final int fanoutBatch = Math.max(512, Math.min(65536, players * 3));
        final int chunkIntentBatch = Math.max(256, Math.min(32768, players * 2));
        final int entityBatch = Math.max(256, Math.min(32768, players * 2));
        final int commitBatch = Math.max(64, Math.min(4096, players / 2));
        return new AGCComputeEfficiencyPlan(
            players,
            tps,
            readOnlyWorkers,
            readOnlyBatch,
            fanoutBatch,
            chunkIntentBatch,
            entityBatch,
            commitBatch,
            reservedCpu,
            "Reserve CPU for primary thread/Netty/GC/plugins; use extra cores only for read-only prepare, fanout de-duplication and ordered commit preparation"
        );
    }

    public @NotNull AGCWildScalePlan wildScalePlan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 2000 ? 22 : 18;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int networkBatch = Math.max(512, Math.min(131072, players * 4));
        final int chunkBatch = Math.max(256, Math.min(65536, players * 2));
        final int entityBatch = Math.max(256, Math.min(65536, players * 2));
        final int cellSize = players >= 1000 ? 64 : 96;
        return new AGCWildScalePlan(
            players,
            tps,
            workers,
            networkBatch,
            chunkBatch,
            entityBatch,
            cellSize,
            reservedCpu,
            true,
            true,
            true,
            "Use AGC alpha14 scale kernel: deduplicate read-only fanout, budget chunk intents, preselect entity candidates, keep plugin events/chunk FIFO/entity ticks ordered"
        );
    }

    public @NotNull AGCScale15Plan scale15Plan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 2000 ? 24 : 20;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int packetShapeBudget = Math.max(1024, Math.min(262144, players * 8));
        final int cohortCell = players >= 1500 ? 64 : 96;
        final int chunkRegion = players >= 1500 ? 32 : 16;
        final int entityCandidates = Math.max(1024, Math.min(8192, players * 4));
        final int pluginBudget = Math.max(512, Math.min(65536, players * 2));
        final long scratchBytes = Math.max(8L * 1024L * 1024L, Math.min(256L * 1024L * 1024L, players * 65536L));
        return new AGCScale15Plan(
            players,
            tps,
            reservedCpu,
            workers,
            packetShapeBudget,
            cohortCell,
            chunkRegion,
            entityCandidates,
            pluginBudget,
            scratchBytes,
            false,
            false,
            false,
            "AGC alpha15: reuse packet shapes, cohort players for read-only planning, compact chunk intents, band entity candidates, keep Bukkit-visible commits ordered"
        );
    }

    public @NotNull AGCScale16Plan scale16Plan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 2000 ? 26 : 22;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int motionHorizon = players >= 1500 ? 3 : 2;
        final int chunkLookaheadRadius = players >= 1500 ? 14 : 10;
        final int entityBandCap = Math.max(2048, Math.min(32768, players * 8));
        final int packetShapeBudget = Math.max(4096, Math.min(524288, players * 12));
        return new AGCScale16Plan(
            players,
            tps,
            reservedCpu,
            workers,
            motionHorizon,
            chunkLookaheadRadius,
            entityBandCap,
            packetShapeBudget,
            true,
            true,
            "AGC alpha16: use PID-like tick control, motion-aware chunk lookahead, stable entity spatial bands, lossless packet delta shapes and a hard-coded Minecraft phase DAG; all Bukkit-visible mutation stays ordered"
        );
    }


    public @NotNull AGCScale17Plan scale17Plan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 2000 ? 28 : 24;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int chunkRadius = players >= 2000 ? 16 : 12;
        final int chunkHints = Math.max(128, Math.min(4096, players));
        final int entityCandidates = Math.max(4096, Math.min(262144, players * 16));
        final int recipientsPerCohort = players >= 2000 ? 64 : 48;
        final int prepareWaves = Math.max(2, Math.min(128, workers * 4));
        return new AGCScale17Plan(
            players,
            tps,
            reservedCpu,
            workers,
            chunkRadius,
            chunkHints,
            entityCandidates,
            recipientsPerCohort,
            prepareWaves,
            true,
            true,
            "AGC alpha17: predictive chunk rings, read-only entity interaction graph, lossless recipient cohorts, compiled world phase prepare waves and strict plugin contract verification; Bukkit-visible changes stay ordered"
        );
    }


    public @NotNull AGCScale18Plan scale18Plan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 2000 ? 30 : 26;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int prepareWaves = Math.max(4, Math.min(512, workers * 4 + players / 96));
        final int orderedBarriers = players >= 2000 ? 5 : 4;
        final int networkCohort = players >= 2000 ? 64 : 48;
        final int chunkRouteRadius = players >= 2000 ? 18 : 14;
        final int entityDeltaBands = Math.max(16, Math.min(128, players / 32));
        final long scratch = Math.max(16L * 1024L * 1024L, Math.min(512L * 1024L * 1024L, players * 96L * 1024L));
        return new AGCScale18Plan(
            players,
            tps,
            reservedCpu,
            workers,
            prepareWaves,
            orderedBarriers,
            networkCohort,
            chunkRouteRadius,
            entityDeltaBands,
            scratch,
            true,
            true,
            true,
            "AGC alpha18: compile one unified semantic tick plan, use lossless network send graphs, stable chunk route hints, read-only entity delta indexes and a plugin semantic firewall; Bukkit-visible commits and chunk FIFO remain ordered"
        );
    }

    public @NotNull AGCScale19Plan scale19Plan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 2000 ? 32 : 28;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int multicastGroups = Math.max(16, Math.min(1024, (players + 47) / 48));
        final int chunkForecastRadius = players >= 2000 ? 22 : 16;
        final int chunkForecastHints = Math.max(256, Math.min(8192, players * 2));
        final int entityInterestBands = Math.max(16, Math.min(256, players / 24));
        final int worldPhaseBatches = Math.max(4, Math.min(512, workers * 6 + players / 128));
        final int pluginSemanticCacheEntries = Math.max(1024, Math.min(16384, players * 4));
        final long localityBytes = Math.max(32L * 1024L * 1024L, Math.min(768L * 1024L * 1024L, players * 128L * 1024L));
        return new AGCScale19Plan(
            players,
            tps,
            reservedCpu,
            workers,
            multicastGroups,
            chunkForecastRadius,
            chunkForecastHints,
            entityInterestBands,
            worldPhaseBatches,
            pluginSemanticCacheEntries,
            localityBytes,
            true,
            true,
            true,
            "AGC alpha19: use lossless multicast shape graphs, chunk demand forecasting, entity interest-set reduction, read-only phase batching, semantic JIT classification and adaptive tick-local locality; Bukkit-visible state changes stay ordered"
        );
    }

    public @NotNull AGCScale20Plan scale20Plan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 2000 ? 34 : 30;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int networkLeaves = Math.max(16, Math.min(4096, (players + 31) / 32));
        final int networkSpines = Math.max(1, Math.min(256, (networkLeaves + 15) / 16));
        final int chunkRadius = players >= 2000 ? 24 : 18;
        final int chunkSlices = Math.max(16, Math.min(512, (chunkRadius * chunkRadius + 31) / 32));
        final int entityBands = Math.max(32, Math.min(512, players / 16));
        final int worldPrepareSlots = Math.max(8, Math.min(512, workers * 8 + players / 128));
        final int pluginSemanticBudget = Math.max(2048, Math.min(32768, players * 6));
        final long localityBytes = Math.max(64L * 1024L * 1024L, Math.min(1024L * 1024L * 1024L, players * 160L * 1024L));
        return new AGCScale20Plan(
            players,
            tps,
            reservedCpu,
            workers,
            networkSpines,
            networkLeaves,
            chunkSlices,
            chunkRadius,
            entityBands,
            worldPrepareSlots,
            pluginSemanticBudget,
            localityBytes,
            true,
            true,
            true,
            true,
            "AGC alpha20: run one lossless scale pipeline with network backbone graphs, FIFO-compatible chunk worksets, read-only entity witness indexes, semantic world slots, deterministic plugin classification and CPU/memory placement; Minecraft-visible state stays ordered"
        );
    }

    public @NotNull AGCScale21Plan scale21Plan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 3000 ? 36 : players >= 2000 ? 34 : 30;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int clusterSize = players >= 3000 ? 64 : 48;
        final int chunkSliceWidth = Math.max(8, Math.min(96, 8192 / Math.max(16, players / 32)));
        final int entityCandidateBudget = Math.max(4096, Math.min(524288, players * 64));
        final int networkCohorts = Math.max(16, Math.min(8192, (players + 31) / 32));
        final int multiverseLanes = Math.max(1, Math.min(512, workers * 8));
        final int pluginTickets = Math.max(512, Math.min(65536, players * 8));
        final long scratchBytes = Math.max(96L * 1024L * 1024L, Math.min(1536L * 1024L * 1024L, players * 192L * 1024L));
        return new AGCScale21Plan(
            players,
            tps,
            reservedCpu,
            workers,
            clusterSize,
            chunkSliceWidth,
            entityCandidateBudget,
            networkCohorts,
            multiverseLanes,
            pluginTickets,
            scratchBytes,
            true,
            true,
            true,
            true,
            "AGC alpha21: use critical-path scheduling, player cluster movement planning, FIFO-preserving chunk storm smoothing, read-only entity density fields, lossless broadcast cohorts, deterministic plugin commit sequencing and multiverse read-only lane allocation; visible Minecraft semantics stay ordered"
        );
    }

    public @NotNull AGCScale22Plan scale22Plan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 3000 ? 40 : players >= 2000 ? 38 : 34;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int tickWorkUnits = Math.max(524288, Math.min(8_388_608, players * 2048));
        final int networkBackboneNodes = Math.max(16, Math.min(4096, (players + 15) / 16));
        final int chunkTerrainStripes = Math.max(16, Math.min(1024, (players + 31) / 32));
        final int entityObserverBands = Math.max(32, Math.min(2048, (int) Math.sqrt(players * 256.0D)));
        final int multiversePhaseWaves = Math.max(1, Math.min(512, workers * 12));
        final int pluginCommitTickets = Math.max(1024, Math.min(131072, players * 12));
        final long scratchBytes = Math.max(128L * 1024L * 1024L, Math.min(2048L * 1024L * 1024L, players * 256L * 1024L));
        return new AGCScale22Plan(
            players,
            tps,
            reservedCpu,
            workers,
            tickWorkUnits,
            networkBackboneNodes,
            chunkTerrainStripes,
            entityObserverBands,
            multiversePhaseWaves,
            pluginCommitTickets,
            scratchBytes,
            true,
            true,
            true,
            true,
            "AGC alpha22: compile tick-local helper work through packet cohort backbones, terrain demand stripes, entity observer matrices, multiverse phase waves, ordered plugin commit ledger and system-load shepherding; visible Minecraft semantics remain ordered"
        );
    }


    public @NotNull AGCScale23Plan scale23Plan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 3000 ? 42 : players >= 2000 ? 40 : 36;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int dispatchSlots = Math.max(524288, Math.min(16_777_216, players * 3072));
        final int networkDiffVectors = Math.max(64, Math.min(16384, (int) Math.sqrt(players * 8192.0D)));
        final int chunkFrontierWidth = Math.max(16, Math.min(512, players >= 3000 ? 192 : players >= 2000 ? 160 : 128));
        final int entityVisibilityBands = Math.max(64, Math.min(4096, (int) Math.sqrt(players * 512.0D)));
        final int worldReadOnlyWaves = Math.max(1, Math.min(1024, workers * 16));
        final int pluginTickets = Math.max(2048, Math.min(262144, players * 16));
        final long scratchBytes = Math.max(192L * 1024L * 1024L, Math.min(3072L * 1024L * 1024L, players * 320L * 1024L));
        return new AGCScale23Plan(
            players,
            tps,
            reservedCpu,
            workers,
            dispatchSlots,
            networkDiffVectors,
            chunkFrontierWidth,
            entityVisibilityBands,
            worldReadOnlyWaves,
            pluginTickets,
            scratchBytes,
            true,
            true,
            true,
            true,
            "AGC alpha23: compile lossless helper dispatch slots, stable packet diff vectors, FIFO chunk frontiers, read-only entity visibility lattices, plugin commit ticket coalescing and world barrier DAGs while keeping visible Minecraft and Bukkit semantics ordered"
        );
    }

    public @NotNull AGCScale24Plan scale24Plan(final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 3000 ? 44 : players >= 2000 ? 42 : 38;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int hotPathCredits = Math.max(524288, Math.min(33_554_432, players * 4096));
        final int planCacheEntries = Math.max(131072, Math.min(4_194_304, players * 1024));
        final int networkOrderVectorLanes = Math.max(128, Math.min(32768, (int) Math.sqrt(players * 16384.0D)));
        final int chunkHorizonWidth = Math.max(32, Math.min(1024, players >= 3000 ? 320 : players >= 2000 ? 256 : 192));
        final int entityObserverSets = Math.max(128, Math.min(16384, (int) Math.sqrt(players * 2048.0D)));
        final int worldReadOnlyBatches = Math.max(1, Math.min(2048, workers * 24));
        final int pluginSemanticTickets = Math.max(4096, Math.min(524288, players * 24));
        final long localityBytes = Math.max(256L * 1024L * 1024L, Math.min(4096L * 1024L * 1024L, players * 384L * 1024L));
        return new AGCScale24Plan(
            players,
            tps,
            reservedCpu,
            workers,
            hotPathCredits,
            planCacheEntries,
            networkOrderVectorLanes,
            chunkHorizonWidth,
            entityObserverSets,
            worldReadOnlyBatches,
            pluginSemanticTickets,
            localityBytes,
            true,
            true,
            true,
            true,
            "AGC alpha24: reduce visible tick hot path with compiled plan caches, lossless network order vectors, FIFO chunk horizons, read-only entity observer-set compilation, world read-only batch graphs, deterministic plugin semantic tickets and locality-aware helper scheduling"
        );
    }


    public boolean isPrimaryThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (final Throwable ignored) {
            return true;
        }
    }

    private static int safeOnlineCount() {
        try {
            return Bukkit.getOnlinePlayers().size();
        } catch (final Throwable ignored) {
            return 0;
        }
    }
}
