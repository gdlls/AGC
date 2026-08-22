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

    /**
     * Unified scaling plan generator for any target AGC scale version.
     *
     * @param version AGC scale version (15..24)
     * @param expectedPlayers estimated concurrent players
     * @param targetTps target ticks per second
     * @return unified scale plan
     */
    public @NotNull AGCScalePlan scalePlan(final int version, final int expectedPlayers, final double targetTps) {
        final int players = Math.max(1, expectedPlayers);
        final double tps = Math.max(1.0D, targetTps);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCpu = players >= 3000 ? 44 : players >= 2000 ? 40 : 30;
        final int workers = Math.max(1, processors - Math.max(2, (processors * reservedCpu + 99) / 100));
        final int workUnits = Math.max(524288, Math.min(33_554_432, players * 4096));
        final int networkBudget = Math.max(128, Math.min(32768, (int) Math.sqrt(players * 16384.0D)));
        final int chunkHorizon = Math.max(32, Math.min(1024, players >= 2000 ? 256 : 128));
        final int entityLimit = Math.max(128, Math.min(16384, (int) Math.sqrt(players * 2048.0D)));
        final long locality = Math.max(256L * 1024L * 1024L, Math.min(4096L * 1024L * 1024L, players * 384L * 1024L));
        final String strategy = "AGC unified scale plan (v" + version + "): compiled plan caches, lossless network vectors, FIFO chunk horizons, read-only observer sets, and locality-aware worker scheduling.";

        return new AGCScalePlan(
            Math.max(1, version),
            players,
            tps,
            reservedCpu,
            workers,
            workUnits,
            networkBudget,
            chunkHorizon,
            entityLimit,
            locality,
            true,
            true,
            true,
            true,
            strategy
        );
    }

    public @NotNull AGCScalePlan scale15Plan(final int expectedPlayers, final double targetTps) {
        return this.scalePlan(15, expectedPlayers, targetTps);
    }

    public @NotNull AGCScalePlan scale16Plan(final int expectedPlayers, final double targetTps) {
        return this.scalePlan(16, expectedPlayers, targetTps);
    }

    public @NotNull AGCScalePlan scale17Plan(final int expectedPlayers, final double targetTps) {
        return this.scalePlan(17, expectedPlayers, targetTps);
    }

    public @NotNull AGCScalePlan scale18Plan(final int expectedPlayers, final double targetTps) {
        return this.scalePlan(18, expectedPlayers, targetTps);
    }

    public @NotNull AGCScalePlan scale19Plan(final int expectedPlayers, final double targetTps) {
        return this.scalePlan(19, expectedPlayers, targetTps);
    }

    public @NotNull AGCScalePlan scale20Plan(final int expectedPlayers, final double targetTps) {
        return this.scalePlan(20, expectedPlayers, targetTps);
    }

    public @NotNull AGCScalePlan scale21Plan(final int expectedPlayers, final double targetTps) {
        return this.scalePlan(21, expectedPlayers, targetTps);
    }

    public @NotNull AGCScalePlan scale22Plan(final int expectedPlayers, final double targetTps) {
        return this.scalePlan(22, expectedPlayers, targetTps);
    }

    public @NotNull AGCScalePlan scale23Plan(final int expectedPlayers, final double targetTps) {
        return this.scalePlan(23, expectedPlayers, targetTps);
    }

    public @NotNull AGCScalePlan scale24Plan(final int expectedPlayers, final double targetTps) {
        return this.scalePlan(24, expectedPlayers, targetTps);
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
