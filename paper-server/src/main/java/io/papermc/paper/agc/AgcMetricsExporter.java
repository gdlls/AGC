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
        root.put("mode", AgcCapabilityMatrix.getMode().name());
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

        return root;
    }
}
