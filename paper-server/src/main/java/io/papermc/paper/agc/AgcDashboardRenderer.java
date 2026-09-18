package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.List;

/**
 * AGC — Real-Time Performance Dashboard Renderer.
 *
 * <p>Formats comprehensive AGC runtime metrics into structured multi-line reports
 * suitable for in-game chat, console output, and diagnostic logs.</p>
 */
public final class AgcDashboardRenderer {

    private static final AgcDashboardRenderer INSTANCE = new AgcDashboardRenderer();

    public static AgcDashboardRenderer get() {
        return INSTANCE;
    }

    private AgcDashboardRenderer() {}

    /**
     * Renders the complete ASCII dashboard.
     *
     * @return List of formatted lines
     */
    public List<String> renderDashboard() {
        final List<String> lines = new ArrayList<>();
        final String mode = "ALL_OPTIMIZATIONS_ACTIVE";
        final String govState = AgcPerformanceGovernor.get().getState().name();

        final var world = AgcParallelWorldTickEngine.get().metrics();
        final var hbr = AgcWorldHibernationEngine.get().metrics();
        final var avd = AgcAdaptiveViewDistanceController.get().metrics();
        final var netBcast = AgcPacketBroadcastDeduplicator.get().metrics();
        final var netFlush = AgcFlushCoalescer.get().metrics();
        final var ear = AgcHierarchicalActivationRange.get().metrics();
        final var ai = AgcEntityAiBatchProcessor.get().metrics();
        final var mem = AgcHotPathCache.recordSnapshot();
        final var direct = AgcDirectBufferPool.get().metrics();
        final var cow = AgcPaletteCowOptimizer.get().metrics();
        final var io = AgcStorageIoGovernor.get().metrics();

        lines.add("==================== [AGC PERFORMANCE DASHBOARD] ====================");
        lines.add(String.format("  Runtime: Mode: %s | Governor: %s | Workers: %d threads",
            mode, govState, world.workers()));
        lines.add(String.format("  Memory: %.1f MB / %.1f MB (%.1f%% used) | Direct Off-Heap: %.1f MB | COW Saved: %.1f MB",
            (mem.totalMemory() - mem.freeMemory()) / (1024.0 * 1024.0),
            mem.maxMemory() / (1024.0 * 1024.0),
            mem.usedMemoryRatio() * 100.0,
            direct.totalAllocatedBytes() / (1024.0 * 1024.0),
            cow.estimatedBytesSaved() / (1024.0 * 1024.0)));
        lines.add("---------------------------------------------------------------------");
        // Two lines on purpose: "parallel ticks" (wave dispatch taken) and "concurrent ticks" (two
        // worlds really overlapped) are different facts, and reporting only the first let the engine
        // advertise parallelism it never delivered. minWorlds/peak wave size make the partition visible.
        lines.add(String.format("  [World Engine] Dispatch: parallel=%,d sequential=%,d ticks | Waves: %,d (Avg: %.2fms) | Failures: %d",
            world.parallelTicks(), world.sequentialTicks(), world.totalWaves(), world.averageWaveMillis(), world.failures()));
        lines.add(String.format("  [World Engine] Concurrency: %,d ticks overlapped (%.1f%%) | Concurrent waves: %,d (peak %d worlds) | Single-world waves: %,d | minWorlds=%d",
            world.concurrentTicks(), world.concurrencyRatio() * 100.0, world.concurrentWaves(),
            world.peakWaveSize(), world.singleWorldWaves(), world.minWorlds()));
        lines.add(String.format("  [500+ Worlds 3-Tier] Active: %d | Warm (RAM): %d | Cold (Disk): %d | Saved: %,d",
            hbr.activeWorlds(), hbr.warmHibernatingWorlds(), hbr.coldDormantWorlds(), hbr.worldTicksSaved()));
        lines.add(String.format("  [Storage I/O] Available Tokens: %.0f | Saves Admitted: %,d | Throttled: %,d",
            io.availableTokens(), io.savesAdmitted(), io.savesThrottled()));
        lines.add(String.format("  [View Distance] Current Target: %d chunks | Adjustments: %,d",
            avd.currentTargetDistance(), avd.totalAdjustments()));
        lines.add("---------------------------------------------------------------------");
        lines.add(String.format("  [500+ Network] Broadcasts: %,d | Serializations Saved: %,d (Zero-Copy)",
            netBcast.broadcasts(), netBcast.serializationsSaved()));
        lines.add(String.format("  [Netty Flush] Coalesced Packets: %,d | Syscall Batches: %,d",
            netFlush.packetsCoalesced(), netFlush.flushesExecuted()));
        lines.add("---------------------------------------------------------------------");
        lines.add(String.format("  [Entity EAR 2.0] Active: %,d | Reduced: %,d | Dormant Skipped: %,d (%.1f%%)",
            ear.activeTicked(), ear.reducedTicked(), ear.dormantSkipped(), ear.skipRatio() * 100.0));
        lines.add(String.format("  [Entity AI] Evaluated: %,d | Batched/Skipped: %,d (CPU Reduction: %.1f%%)",
            ai.goalsEvaluated(), ai.goalsSkipped(), ai.cpuReductionRatio() * 100.0));
        lines.add("=====================================================================");

        return lines;
    }
}
