package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * AGC — Unified Hot-Path Runtime Dispatch Bridge.
 *
 * <p>Acts as the master high-throughput wiring harness connecting all 53 AGC optimization
 * engines directly into the Minecraft/Paper server tick loop, combat listener, network layer,
 * entity physics pipeline, chunk storage, and governor subsystems.</p>
 */
public final class AgcHotPathRuntimeBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcHotPathRuntimeBridge.class);
    private static final AgcHotPathRuntimeBridge INSTANCE = new AgcHotPathRuntimeBridge();

    // Hot-Path Invocation Counters
    private final AtomicLong totalServerTicks = new AtomicLong();
    private final AtomicLong totalWorldTicksDispatched = new AtomicLong();
    private final AtomicLong totalWorldTicksHibernated = new AtomicLong();
    private final AtomicLong totalCombatInteractions = new AtomicLong();
    private final AtomicLong totalSimdCollisions = new AtomicLong();
    private final AtomicLong totalZeroCopyBroadcasts = new AtomicLong();
    private final AtomicLong totalChunkOperationsAdmitted = new AtomicLong();
    private volatile double lastMspt = 5.0; // nominal baseline default
    private volatile boolean tickActive = false;
    private volatile long currentTickStartNanos = 0L;

    public static AgcHotPathRuntimeBridge get() {
        return INSTANCE;
    }

    public double getLastMspt() {
        return this.lastMspt;
    }

    public boolean isTickActive() {
        return this.tickActive;
    }

    public double getCurrentTickElapsedMs() {
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server != null && server.currentTickStart > 0) {
            final double elapsed = (System.nanoTime() - server.currentTickStart) / 1_000_000.0;
            if (elapsed >= 0.0 && elapsed < 5000.0) {
                return elapsed;
            }
        }
        if (!this.tickActive) return 0.0;
        return (System.nanoTime() - this.currentTickStartNanos) / 1_000_000.0;
    }

    private AgcHotPathRuntimeBridge() {
        LOGGER.info("[AGC] Hot-Path Runtime Dispatch Bridge initialized.");
    }

    // 1. Server Tick Lifecycle & Performance Governance

    /**
     * Invoked at the very beginning of {@code MinecraftServer.tickServer()}.
     */
    public void onServerTickStart(final long tickCount) {
        this.currentTickStartNanos = System.nanoTime();
        this.tickActive = true;
        this.totalServerTicks.incrementAndGet();
        io.papermc.paper.agc.profiling.AgcTickProfiler.get().startTick(tickCount);
        io.papermc.paper.agc.chunk.AgcChunkGenThrottler.get().onTickStart();
    }

    /**
     * Invoked at the very end of {@code MinecraftServer.tickServer()}.
     *
     * @param tickDurationNanos Elapsed duration of the tick in nanoseconds
     * @param tickCount         Current server tick number
     */
    public void onServerTickEnd(final long tickDurationNanos, final long tickCount) {
        this.tickActive = false;
        final double mspt = tickDurationNanos / 1_000_000.0;
        this.lastMspt = mspt;
        // AGC - feature scoper: this is the only place in the server that knows the exact duration of the
        // tick that just ended, which is what makes a paired ON/OFF A/B on one live JVM possible at all
        // (see AgcFeatureScoper). A no-op returning immediately unless a scope run is active.
        AgcFeatureScoper.get().onTickEnd(tickCount, mspt);
        final Runtime runtime = Runtime.getRuntime();
        final long freeMem = runtime.freeMemory();
        final long totalMem = runtime.totalMemory();
        final long maxMem = runtime.maxMemory();
        final double freeMemPercent = maxMem > 0 ? ((maxMem - Math.max(0L, totalMem - freeMem)) / (double) maxMem) * 100.0 : 50.0;
        final double usedMemRatio = maxMem > 0 ? Math.min(1.0, Math.max(0.0, (totalMem - freeMem) / (double) maxMem)) : 0.5;

        // Read the live count once for telemetry. The previous implementation passed a
        // hard-coded 100, which made a 0-player server look congested and made the
        // governor oscillate during login churn.
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        final int activePlayers = server != null && server.getPlayerList() != null
            ? server.getPlayerList().getPlayerCount()
            : 0;

        // 1. Commit Tick Profiler & Memory Telemetry
        io.papermc.paper.agc.profiling.AgcTickProfiler.get().endTick(tickCount, tickDurationNanos);
        io.papermc.paper.agc.profiling.AgcMemoryTracker.get().onTick(tickCount, mspt);

        // Governance is evaluated by MinecraftServer once per rolling telemetry window.
        // Keeping one authoritative evaluation site avoids duplicate transitions and log
        // storms on the hot tick-end path.

        // 2. Flush Coalesced Network Buffers & Tick Explosion State
        AgcFlushCoalescer.get().flushPendingChannels();
        io.papermc.paper.agc.explosion.AgcExplosionOptimizer.get().onTickEnd();

        // 3. Record Periodic Telemetry
        if (tickCount % 100 == 0) {
            final AgcPerformanceGovernor.State govState = AgcPerformanceGovernor.get().getState();
            if (govState != AgcPerformanceGovernor.State.HEALTHY) {
                AgcStabilityJournal.get().record(
                    AgcStabilityJournal.EventType.GOVERNOR_ACTION,
                    "ServerTick",
                    "Governor active state: " + govState + " (MSPT=" + String.format("%.2f", mspt) + "ms, Players=" + activePlayers + ")"
                );
            }
        }
    }

    // 2. Parallel Multi-World Tick & Instant Hibernation Pipeline

    /**
     * Ticks all server levels using parallel conflict waves and instant hibernation.
     *
     * @param worlds             List of world handles
     * @param nameExtractor      Function mapping world handle to world name
     * @param playerExtractor    Function mapping world handle to active player count
     * @param worldTicker        Per-world single-thread tick runner
     * @param haveTime           Supplier indicating remaining tick time budget
     * @param <T>                World handle type (e.g. ServerLevel)
     */
    public <T> void tickWorlds(
        final List<T> worlds,
        final Function<T, String> nameExtractor,
        final ToIntFunction<T> playerExtractor,
        final Consumer<T> worldTicker,
        final BooleanSupplier haveTime
    ) {
        if (worlds == null || worlds.isEmpty()) {
            return;
        }

        final List<T> activeWorlds = new ArrayList<>(worlds.size());
        final boolean hibernationEnabled = AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.MULTIWORLD_UNLOAD);
        final long tick = this.totalServerTicks.get();

        for (final T world : worlds) {
            final String worldName = nameExtractor.apply(world);
            final int players = playerExtractor.applyAsInt(world);

            if (hibernationEnabled) {
                // Protect primary survival worlds (overworld, nether, the_end) from hibernation to guarantee
                // 20 TPS for portal chunk loaders, iron farms, and automated factories.
                final boolean isPrimaryDimension = worldName != null && (
                    worldName.equals("world") || worldName.equals("world_nether") || worldName.equals("world_the_end")
                    || worldName.endsWith("_nether") || worldName.endsWith("_the_end")
                );

                if (!isPrimaryDimension) {
                    final AgcWorldHibernationEngine.WorldState state =
                        AgcWorldHibernationEngine.get().updateWorld(worldName, players, tick, 600L); // 30-second graceful countdown

                    if (state == AgcWorldHibernationEngine.WorldState.COLD) {
                        // 0ms CPU time: completely skipped for purely dormant arena worlds
                        this.totalWorldTicksHibernated.incrementAndGet();
                        continue;
                    } else if (state == AgcWorldHibernationEngine.WorldState.HIBERNATING) {
                        // Throttled: ticked once every 20 ticks (1 Hz)
                        if (tick % 20 != 0) {
                            this.totalWorldTicksHibernated.incrementAndGet();
                            continue;
                        }
                    }
                }
            }

            activeWorlds.add(world);
        }

        // Execute active worlds in parallel waves or sequential fallback
        final int minParallelWorlds = 2;
        AgcParallelWorldTickEngine.get().executeWorldTicks(activeWorlds, worldTicker, minParallelWorlds);
        this.totalWorldTicksDispatched.addAndGet(activeWorlds.size());

        // Immediately drain cross-world operations, region commits, and plugin safety mailboxes post-barrier
        AgcCrossWorldQueue.get().drainAll();
        io.papermc.paper.agc.tick.AgcRegionTickBridge.get().drainCommits();
        AgcPluginSafetyGuard.get().drainMailbox(0);
    }

    // 3. Singleplayer-Feel Combat & KBSync Sub-Tick Dispatch

    /**
     * Processes a combat knockback trajectory with sub-tick immediate dispatch (with attacker context).
     */
    public boolean processCombatKnockback(
        final UUID victimId,
        final UUID attackerId,
        final double vx,
        final double vy,
        final double vz,
        final double distanceToGround,
        final boolean attackerSprinting,
        final double attackerCooldown,
        final int knockbackLevel,
        final double knockbackResistance,
        final Runnable immediatePacketDispatcher
    ) {
        this.totalCombatInteractions.incrementAndGet();
        final AgcSingleplayerFeelCombatEngine engine = AgcSingleplayerFeelCombatEngine.getInstance();
        if (!engine.isEnabled()) {
            return false;
        }

        final AgcSingleplayerFeelCombatEngine.PlayerCombatState victimState = engine.getOrCreateState(victimId);
        final AgcSingleplayerFeelCombatEngine.PlayerCombatState attackerState = attackerId != null ? engine.getOrCreateState(attackerId) : null;
        final AgcSingleplayerFeelCombatEngine.CombatVector3 original =
            new AgcSingleplayerFeelCombatEngine.CombatVector3(vx, vy, vz);

        final AgcSingleplayerFeelCombatEngine.CombatVector3 compensated = engine.calculateKnockbackTrajectory(
            victimState, attackerState, original, distanceToGround, attackerSprinting, attackerCooldown, knockbackLevel, knockbackResistance
        );

        return engine.dispatchSubTickKnockback(victimState, compensated, immediatePacketDispatcher);
    }

    /**
     * Processes a combat knockback trajectory with sub-tick immediate dispatch.
     */
    public boolean processCombatKnockback(
        final UUID victimId,
        final double vx,
        final double vy,
        final double vz,
        final double distanceToGround,
        final boolean attackerSprinting,
        final double attackerCooldown,
        final int knockbackLevel,
        final double knockbackResistance,
        final Runnable immediatePacketDispatcher
    ) {
        return processCombatKnockback(
            victimId, null, vx, vy, vz, distanceToGround, attackerSprinting, attackerCooldown, knockbackLevel, knockbackResistance, immediatePacketDispatcher
        );
    }

    /**
     * Processes a projectile knockback (fishing rod, arrow, snowball, egg).
     */
    public boolean processProjectileKnockback(
        final UUID victimId,
        final double vx,
        final double vy,
        final double vz,
        final boolean isFishingRod,
        final Runnable immediatePacketDispatcher
    ) {
        this.totalCombatInteractions.incrementAndGet();
        final AgcSingleplayerFeelCombatEngine engine = AgcSingleplayerFeelCombatEngine.getInstance();
        if (!engine.isEnabled()) {
            return false;
        }

        final AgcSingleplayerFeelCombatEngine.PlayerCombatState victimState = engine.getOrCreateState(victimId);
        victimState.setProjectileDamage(true);
        final AgcSingleplayerFeelCombatEngine.CombatVector3 motion =
            new AgcSingleplayerFeelCombatEngine.CombatVector3(vx, vy, vz);

        return engine.dispatchSubTickKnockback(victimState, motion, immediatePacketDispatcher);
    }

    // 4. SIMD Vectorized Collision & SOA Physics Sweep

    /**
     * Executes SIMD vectorized AABB collision detection.
     */
    public int sweepSimdCollisions(
        final float targetMinX, final float targetMinY, final float targetMinZ,
        final float targetMaxX, final float targetMaxY, final float targetMaxZ,
        final float[] candidateMinX, final float[] candidateMinY, final float[] candidateMinZ,
        final float[] candidateMaxX, final float[] candidateMaxY, final float[] candidateMaxZ,
        final int count,
        final int[] collidedIndices
    ) {
        this.totalSimdCollisions.incrementAndGet();
        return AgcSimdCollisionKernel.get().sweep64(
            targetMinX, targetMinY, targetMinZ, targetMaxX, targetMaxY, targetMaxZ,
            candidateMinX, candidateMinY, candidateMinZ, candidateMaxX, candidateMaxY, candidateMaxZ,
            count,
            collidedIndices
        );
    }

    /**
     * Executes SOA entity physics step.
     */
    public void stepSoaPhysics(final float dt) {
        AgcSoaEntityPhysicsEngine.get().stepMotionAll(dt);
    }

    // 5. Zero-Copy Spine-Leaf Network Fanout & Delta Entity Tracking

    /**
     * Dispatches a serialized packet buffer to subscribers with zero memory copying.
     */
    public <T> int broadcastZeroCopy(
        final int packetId,
        final byte[] payload,
        final Collection<T> subscribers,
        final BiConsumer<T, byte[]> sender
    ) {
        this.totalZeroCopyBroadcasts.incrementAndGet();
        return AgcZeroCopyBroadcastHub.get().broadcast(packetId, payload, subscribers, sender);
    }

    /**
     * Computes the 64-bit dirty delta bitmask for an entity update.
     */
    public long computeEntityDeltaMask(
        final AgcBitLevelDeltaEntityTracker.EntityState current,
        final AgcBitLevelDeltaEntityTracker.EntityState previous
    ) {
        return AgcBitLevelDeltaEntityTracker.get().computeDirtyMask(current, previous);
    }

    // 6. Chunk I/O Admission & Storage Governance

    /**
     * Admits a chunk load/save operation under token quota.
     */
    public boolean admitChunkOperation(final int chunkCount) {
        this.totalChunkOperationsAdmitted.addAndGet(chunkCount);
        return AgcStorageIoGovernor.get().tryAcquire(AgcStorageIoGovernor.SavePriority.CRITICAL_HOT, chunkCount);
    }

    // 7. Advanced Subsystems Fast-Path Hooks

    /**
     * Coalesces section lighting calculation requests.
     */
    public boolean queueLightUpdate(final int chunkX, final int sectionY, final int chunkZ, final boolean blockLight, final boolean skyLight) {
        return io.papermc.paper.agc.light.AgcStarLightBatchOptimizer.get().queueCoalescedSectionUpdate(
            chunkX, sectionY, chunkZ, blockLight, skyLight
        );
    }

    /**
     * Resolves and caches destination container for hoppers.
     */
    public Object resolveTargetContainer(final long hopperPackedPos, final java.util.function.Supplier<Object> resolver) {
        return io.papermc.paper.agc.hopper.AgcHopperOptimizer.get().getOrResolveTargetContainer(hopperPackedPos, resolver);
    }

    /**
     * Redstone redundant update filter.
     */
    public boolean filterRedundantRedstone(final int prevPower, final int newPower) {
        return io.papermc.paper.agc.redstone.AgcRedstoneOptimizer.get().filterRedundantUpdate(prevPower, newPower);
    }

    /**
     * High-speed VarInt encoding fast-path.
     */
    public int writeVarInt(final byte[] buffer, final int offset, final int value) {
        return io.papermc.paper.agc.network.AgcFastNetworkSerializationEngine.get().writeVarInt(buffer, offset, value);
    }

    /**
     * High-speed VarLong encoding fast-path.
     */
    public int writeVarLong(final byte[] buffer, final int offset, final long value) {
        return io.papermc.paper.agc.network.AgcFastNetworkSerializationEngine.get().writeVarLong(buffer, offset, value);
    }

    /**
     * High-speed VarInt decoding fast-path.
     */
    public int readVarInt(final byte[] buffer, final int offset, final int[] bytesRead) {
        return io.papermc.paper.agc.network.AgcFastNetworkSerializationEngine.get().readVarInt(buffer, offset, bytesRead);
    }

    /**
     * High-speed VarLong decoding fast-path.
     */
    public long readVarLong(final byte[] buffer, final int offset, final int[] bytesRead) {
        return io.papermc.paper.agc.network.AgcFastNetworkSerializationEngine.get().readVarLong(buffer, offset, bytesRead);
    }

    /**
     * Lock-free hierarchical event dispatch.
     */
    public <E> int fireEvent(final E event) {
        return io.papermc.paper.agc.event.AgcLockFreeEventDispatcher.get().fireEvent(event);
    }

    /**
     * Line-of-sight raycast cache query (Gale / Purpur port).
     */
    public int getCachedLos(final net.minecraft.world.entity.LivingEntity source, final net.minecraft.world.entity.Entity target, final long currentTick) {
        return io.papermc.paper.agc.entity.AgcLineOfSightCache.get().getCachedLos(source, target, currentTick);
    }

    /**
     * Records line-of-sight determination into raycast cache.
     */
    public void putCachedLos(final net.minecraft.world.entity.LivingEntity source, final net.minecraft.world.entity.Entity target, final boolean visible, final long currentTick) {
        io.papermc.paper.agc.entity.AgcLineOfSightCache.get().putCachedLos(source, target, visible, currentTick);
    }

    /**
     * High-density lossless XP orb merge (Clumps port).
     */
    public boolean tryMergeXp(final net.minecraft.world.entity.ExperienceOrb primary, final net.minecraft.world.entity.ExperienceOrb secondary) {
        return io.papermc.paper.agc.entity.AgcEntityMergeOptimizer.get().tryMergeXp(primary, secondary);
    }

    /**
     * High-density lossless ItemEntity merge (ServerCore port).
     */
    public boolean tryMergeItems(final net.minecraft.world.entity.item.ItemEntity primary, final net.minecraft.world.entity.item.ItemEntity secondary) {
        return io.papermc.paper.agc.entity.AgcEntityMergeOptimizer.get().tryMergeItems(primary, secondary);
    }

    /**
     * Synchronizes a block state change across Project Panama off-heap storage and lock-free RCU chunk map.
     */
    public void onBlockStateChanged(
        final String worldName,
        final int chunkX,
        final int chunkZ,
        final int sectionY,
        final int blockIndex,
        final int oldStateId,
        final int newStateId
    ) {
        if (worldName == null) return;
        final boolean rcuEnabled = AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.CHUNK_L1_L2_CACHE);
        if (rcuEnabled) {
            if (sectionY >= 0 && sectionY < 24 && blockIndex >= 0 && blockIndex < 4096) {
                AgcPanamaOffHeapChunkStorage.get().setBlockState(worldName, chunkX, chunkZ, sectionY, blockIndex, (short) newStateId);
            }
            AgcLockFreeRcuChunkMap.get().updateSection(worldName, chunkX, chunkZ, sectionY, prevSnapshot -> {
                if (prevSnapshot != null) {
                    return prevSnapshot.withBlock(blockIndex, (short) newStateId);
                }
                final short[] data = new short[4096];
                if (blockIndex >= 0 && blockIndex < data.length) {
                    data[blockIndex] = (short) newStateId;
                }
                return new AgcLockFreeRcuChunkMap.RcuSectionSnapshot(sectionY, 1L, data);
            });
        }
        final AgcOptimisticTransactionManager txMgr = AgcOptimisticTransactionManager.get();
        if (txMgr.currentTransaction() != null) {
            final long packed = (((long) blockIndex) << 32) | (((long) chunkX & 0xFFFF) << 16) | ((long) chunkZ & 0xFFFF);
            txMgr.recordBlockMutation(worldName, packed, oldStateId, newStateId, null);
        }
    }

    /**
     * Registers a loaded chunk in the L1 hot chunk cache hierarchy.
     */
    public void onChunkLoaded(final String worldName, final int chunkX, final int chunkZ, final Object chunk) {
        if (worldName == null || chunk == null) return;
        io.papermc.paper.agc.chunk.AgcChunkCacheHierarchy.get().putL1(worldName, chunkX, chunkZ, chunk);
    }

    /**
     * Evicts an unloaded chunk from all cache tiers and off-heap/RCU maps.
     */
    public void onChunkUnloaded(final String worldName, final int chunkX, final int chunkZ) {
        if (worldName == null) return;
        io.papermc.paper.agc.chunk.AgcChunkCacheHierarchy.get().invalidate(worldName, chunkX, chunkZ);
        AgcLockFreeRcuChunkMap.get().removeChunk(worldName, chunkX, chunkZ);
        AgcPanamaOffHeapChunkStorage.get().removeChunk(worldName, chunkX, chunkZ);
    }

    /**
     * Dispatches a broadcast packet with deduplication across subscribers.
     */
    public <T> void broadcastPacketDeduplicated(
        final Object packet,
        final Collection<T> subscribers,
        final Consumer<T> sender
    ) {
        if (subscribers == null || subscribers.isEmpty()) return;
        if (subscribers.size() == 1) {
            for (final T sub : subscribers) {
                sender.accept(sub);
            }
            return;
        }
        AgcPacketBroadcastDeduplicator.get().broadcastSingleSerialization(
            () -> packet,
            subscribers,
            (sub, p) -> sender.accept(sub)
        );
    }

    // 7. Telemetry & Metrics

    public record BridgeMetrics(
        long totalServerTicks,
        long totalWorldTicksDispatched,
        long totalWorldTicksHibernated,
        long totalCombatInteractions,
        long totalSimdCollisions,
        long totalZeroCopyBroadcasts,
        long totalChunkOperationsAdmitted
    ) {}

    public BridgeMetrics metrics() {
        return new BridgeMetrics(
            this.totalServerTicks.get(),
            this.totalWorldTicksDispatched.get(),
            this.totalWorldTicksHibernated.get(),
            this.totalCombatInteractions.get(),
            this.totalSimdCollisions.get(),
            this.totalZeroCopyBroadcasts.get(),
            this.totalChunkOperationsAdmitted.get()
        );
    }
}
