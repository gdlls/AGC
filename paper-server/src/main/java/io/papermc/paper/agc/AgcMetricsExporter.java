package io.papermc.paper.agc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AGC — Spark & Timings Metrics Exporter.
 *
 * <p>Aggregates runtime telemetry from all AGC performance subsystems (parallel world tick engine,
 * broadcast deduplicator, Netty flush coalescer, packet priority scheduler, EAR, AI batcher,
 * spatial index, world hibernation engine, adaptive view distance controller, DRR chunk arbiter,
 * object pools, and governor) into unified diagnostic maps and JSON-compatible structures.</p>
 */
public final class AgcMetricsExporter {

    private static final AgcMetricsExporter INSTANCE = new AgcMetricsExporter();

    public static AgcMetricsExporter get() {
        return INSTANCE;
    }

    private AgcMetricsExporter() {}

    /**
     * Exports a comprehensive snapshot of all AGC subsystem metrics.
     *
     * @return Map containing nested metric categories and values
     */
    public Map<String, Object> exportAll() {
        final Map<String, Object> root = new LinkedHashMap<>();

        // 1. General Status & Mode
        root.put("mode", "ALL_OPTIMIZATIONS_ACTIVE");
        root.put("governor_state", AgcPerformanceGovernor.get().getState().name());

        // 2. Parallel World Tick Engine
        final var worldMetrics = AgcParallelWorldTickEngine.get().metrics();
        final Map<String, Object> worldMap = new LinkedHashMap<>();
        worldMap.put("running", worldMetrics.running());
        worldMap.put("workers", worldMetrics.workers());
        worldMap.put("parallel_ticks", worldMetrics.parallelTicks());
        worldMap.put("sequential_ticks", worldMetrics.sequentialTicks());
        worldMap.put("total_waves", worldMetrics.totalWaves());
        worldMap.put("average_wave_ms", worldMetrics.averageWaveMillis());
        worldMap.put("failures", worldMetrics.failures());
        // "parallel_ticks" is the dispatch branch, not evidence of overlap. These are: only a tick with
        // at least one multi-world wave really ran two worlds at once. See EngineMetrics.concurrencyRatio().
        worldMap.put("concurrent_ticks", worldMetrics.concurrentTicks());
        worldMap.put("concurrent_waves", worldMetrics.concurrentWaves());
        worldMap.put("single_world_waves", worldMetrics.singleWorldWaves());
        worldMap.put("peak_wave_size", worldMetrics.peakWaveSize());
        worldMap.put("concurrency_ratio", worldMetrics.concurrencyRatio());
        worldMap.put("min_worlds", worldMetrics.minWorlds());
        root.put("world_engine", worldMap);

        // 3. Network Subsystems
        final var bcastMetrics = AgcPacketBroadcastDeduplicator.get().metrics();
        final var flushMetrics = AgcFlushCoalescer.get().metrics();
        final var schedMetrics = AgcPacketPriorityScheduler.get().metrics();
        final Map<String, Object> netMap = new LinkedHashMap<>();
        netMap.put("broadcasts", bcastMetrics.broadcasts());
        netMap.put("recipients_served", bcastMetrics.recipientsServed());
        netMap.put("serializations_saved", bcastMetrics.serializationsSaved());
        netMap.put("bytes_dispatched", bcastMetrics.bytesDispatched());
        netMap.put("flush_batches", flushMetrics.flushesExecuted());
        netMap.put("packets_coalesced", flushMetrics.packetsCoalesced());
        netMap.put("immediate_flushes", flushMetrics.immediateFlushes());
        netMap.put("critical_packets", schedMetrics.criticalPackets());
        netMap.put("interactive_packets", schedMetrics.interactivePackets());
        netMap.put("bulk_packets", schedMetrics.bulkPackets());
        root.put("network", netMap);

        // 4. Entity & AI Subsystems
        final var earMetrics = AgcHierarchicalActivationRange.get().metrics();
        final var aiMetrics = AgcEntityAiBatchProcessor.get().metrics();
        final Map<String, Object> entityMap = new LinkedHashMap<>();
        entityMap.put("active_ticked", earMetrics.activeTicked());
        entityMap.put("reduced_ticked", earMetrics.reducedTicked());
        entityMap.put("slow_ticked", earMetrics.slowTicked());
        entityMap.put("dormant_skipped", earMetrics.dormantSkipped());
        entityMap.put("ear_skip_ratio", earMetrics.skipRatio());
        entityMap.put("goals_evaluated", aiMetrics.goalsEvaluated());
        entityMap.put("goals_skipped", aiMetrics.goalsSkipped());
        entityMap.put("ai_cpu_reduction", aiMetrics.cpuReductionRatio());
        root.put("entity_ai", entityMap);

        // 5. Chunk & World Hibernation
        final var hbrMetrics = AgcWorldHibernationEngine.get().metrics();
        final var avdMetrics = AgcAdaptiveViewDistanceController.get().metrics();
        final Map<String, Object> chunkMap = new LinkedHashMap<>();
        chunkMap.put("total_worlds", hbrMetrics.totalTrackedWorlds());
        chunkMap.put("active_worlds", hbrMetrics.activeWorlds());
        chunkMap.put("warm_hibernating_worlds", hbrMetrics.warmHibernatingWorlds());
        chunkMap.put("cold_dormant_worlds", hbrMetrics.coldDormantWorlds());
        chunkMap.put("world_ticks_saved", hbrMetrics.worldTicksSaved());
        chunkMap.put("target_view_distance", avdMetrics.currentTargetDistance());
        chunkMap.put("view_distance_adjustments", avdMetrics.totalAdjustments());
        root.put("chunk_worlds", chunkMap);

        // 6. Memory, Palette COW & Off-Heap Buffers
        final var directMetrics = AgcDirectBufferPool.get().metrics();
        final var cowMetrics = AgcPaletteCowOptimizer.get().metrics();
        final Map<String, Object> memMap = new LinkedHashMap<>();
        memMap.put("direct_buffers_acquired", directMetrics.acquired());
        memMap.put("direct_buffers_released", directMetrics.released());
        memMap.put("direct_bytes_allocated", directMetrics.totalAllocatedBytes());
        memMap.put("cow_sections_optimized", cowMetrics.sectionsOptimized());
        memMap.put("cow_expansions", cowMetrics.cowExpansions());
        memMap.put("cow_bytes_saved", cowMetrics.estimatedBytesSaved());
        root.put("memory", memMap);

        // 7. Storage I/O Governor
        final var ioMetrics = AgcStorageIoGovernor.get().metrics();
        final Map<String, Object> ioMap = new LinkedHashMap<>();
        ioMap.put("tokens_available", ioMetrics.availableTokens());
        ioMap.put("pending_saves", ioMetrics.pendingQueueSize());
        ioMap.put("saves_admitted", ioMetrics.savesAdmitted());
        ioMap.put("saves_throttled", ioMetrics.savesThrottled());
        root.put("storage_io", ioMap);

        // 8. Behavioral Parity Verification
        final var parityMetrics = AgcBehaviorParitySuite.get().metrics();
        final Map<String, Object> parityMap = new LinkedHashMap<>();
        parityMap.put("total_rules", parityMetrics.totalRules());
        parityMap.put("checks_executed", parityMetrics.checksExecuted());
        parityMap.put("deviations_allowed", parityMetrics.deviationsAllowed());
        parityMap.put("violations_detected", parityMetrics.violationsDetected());
        parityMap.put("is_compliant", parityMetrics.isFullyCompliant());
        root.put("behavior_parity", parityMap);

        // 9. Advanced Optimization Engines Telemetry
        final var light = io.papermc.paper.agc.light.AgcStarLightBatchOptimizer.get().metrics();
        final Map<String, Object> lightMap = new LinkedHashMap<>();
        lightMap.put("pooled_nibbles", light.pooledNibbleCount());
        lightMap.put("nibbles_acquired", light.nibblesAcquired());
        lightMap.put("updates_coalesced", light.updatesCoalesced());
        lightMap.put("sky_fastpaths", light.skyBitmaskFastPaths());
        root.put("starlight_batching", lightMap);

        final var chunkCache = io.papermc.paper.agc.chunk.AgcChunkCacheHierarchy.get().metrics();
        final Map<String, Object> chunkCacheMap = new LinkedHashMap<>();
        chunkCacheMap.put("l1_hits", chunkCache.l1Hits());
        chunkCacheMap.put("l2_hits", chunkCache.l2Hits());
        chunkCacheMap.put("l1_evictions", chunkCache.l1Evictions());
        chunkCacheMap.put("deduplicated_loads", chunkCache.deduplicatedLoads());
        chunkCacheMap.put("prefetch_triggers", chunkCache.prefetchTriggers());
        root.put("chunk_cache_hierarchy", chunkCacheMap);

        final var redstone = io.papermc.paper.agc.redstone.AgcRedstoneOptimizer.get().metrics();
        final Map<String, Object> redstoneMap = new LinkedHashMap<>();
        redstoneMap.put("redundant_filtered", redstone.redundantUpdatesFiltered());
        redstoneMap.put("observer_loops_broken", redstone.observerLoopsBroken());
        redstoneMap.put("boundary_cache_hits", redstone.boundaryCacheHits());
        redstoneMap.put("clocks_suppressed", redstone.clockLagMachinesSuppressed());
        redstoneMap.put("wires_coalesced", redstone.wireUpdatesCoalesced());
        root.put("redstone_optimizer", redstoneMap);

        final var hopper = io.papermc.paper.agc.hopper.AgcHopperOptimizer.get().metrics();
        final Map<String, Object> hopperMap = new LinkedHashMap<>();
        hopperMap.put("target_hits", hopper.targetContainerCacheHits());
        hopperMap.put("double_chest_hits", hopper.doubleChestCacheHits());
        hopperMap.put("bitmask_checks", hopper.bitmaskFastChecks());
        hopperMap.put("ticks_skipped", hopper.hopperTicksSkipped());
        root.put("hopper_optimizer", hopperMap);

        final var villager = io.papermc.paper.agc.villager.AgcVillagerOptimizer.get().metrics();
        final Map<String, Object> villagerMap = new LinkedHashMap<>();
        villagerMap.put("poi_queries_saved", villager.poiQueriesSaved());
        villagerMap.put("pathfinding_throttled", villager.pathfindingThrottled());
        villagerMap.put("price_cache_hits", villager.priceCacheHits());
        villagerMap.put("gossip_throttled", villager.gossipThrottled());
        villagerMap.put("golem_checks_throttled", villager.golemChecksThrottled());
        root.put("villager_optimizer", villagerMap);

        final var netCodec = io.papermc.paper.agc.network.AgcFastNetworkSerializationEngine.get().metrics();
        final Map<String, Object> netCodecMap = new LinkedHashMap<>();
        netCodecMap.put("varints_encoded", netCodec.varIntsEncoded());
        netCodecMap.put("varlongs_encoded", netCodec.varLongsEncoded());
        netCodecMap.put("compression_bypassed", netCodec.compressionBypassed());
        netCodecMap.put("buffers_recycled", netCodec.buffersRecycled());
        root.put("network_codec", netCodecMap);

        final var dispatcher = io.papermc.paper.agc.event.AgcLockFreeEventDispatcher.get().metrics();
        final Map<String, Object> dispMap = new LinkedHashMap<>();
        dispMap.put("events_dispatched", dispatcher.eventsDispatched());
        dispMap.put("listeners_invoked", dispatcher.listenersInvoked());
        dispMap.put("cancelled_skipped", dispatcher.cancelledListenersSkipped());
        root.put("lockfree_event_dispatcher", dispMap);

        final var slab = io.papermc.paper.agc.memory.AgcOffHeapSlabAllocator.get().metrics();
        final Map<String, Object> slabMap = new LinkedHashMap<>();
        slabMap.put("slab_1k", slab.slab1kAcquisitions());
        slabMap.put("slab_4k", slab.slab4kAcquisitions());
        slabMap.put("slab_16k", slab.slab16kAcquisitions());
        slabMap.put("slab_64k", slab.slab64kAcquisitions());
        slabMap.put("total_releases", slab.totalReleases());
        root.put("offheap_slab_allocator", slabMap);

        final var vector = io.papermc.paper.agc.simd.AgcVectorMath.get().metrics();
        final Map<String, Object> vectorMap = new LinkedHashMap<>();
        vectorMap.put("batch_aabb_tests", vector.batchAabbTests());
        vectorMap.put("vector_distances", vector.vectorDistanceCalculations());
        vectorMap.put("batch_manhattan", vector.batchManhattanCalculations());
        vectorMap.put("vector_ops", vector.vectorOperations());
        root.put("vector_math_simd", vectorMap);

        return root;
    }
}
