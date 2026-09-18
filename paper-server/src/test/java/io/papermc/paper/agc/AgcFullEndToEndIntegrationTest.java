package io.papermc.paper.agc;

import io.papermc.paper.agc.chat.AgcChatOptimizer;
import io.papermc.paper.agc.command.AgcCommandOptimizer;
import io.papermc.paper.agc.ds.AgcFastHasher;
import io.papermc.paper.agc.ds.AgcSpatialGrid;
import io.papermc.paper.agc.ds.AgcTimingWheel;
import io.papermc.paper.agc.explosion.AgcExplosionOptimizer;
import io.papermc.paper.agc.io.AgcAsyncSavePipeline;
import io.papermc.paper.agc.io.AgcPlayerDataOptimizer;
import io.papermc.paper.agc.io.AgcRegionFileManager;
import io.papermc.paper.agc.jit.AgcTypeDispatcher;
import io.papermc.paper.agc.metrics.AgcPrometheusExporter;
import io.papermc.paper.agc.nativex.AgcNativeAccelerator;
import io.papermc.paper.agc.profiling.AgcMemoryTracker;
import io.papermc.paper.agc.scoreboard.AgcScoreboardOptimizer;
import io.papermc.paper.agc.selfhealing.AgcSelfHealingEngine;
import io.papermc.paper.agc.simd.AgcVectorMath;
import io.papermc.paper.agc.spawner.AgcSpawnerOptimizer;
import io.papermc.paper.agc.villager.AgcVillagerOptimizer;
import io.papermc.paper.agc.worldgen.AgcFeatureOptimizer;
import io.papermc.paper.agc.worldgen.AgcNoiseOptimizer;
import io.papermc.paper.agc.worldgen.AgcStructureOptimizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AGC — Comprehensive End-to-End Integration Test (Phases 0 through 15).
 *
 * <p>Validates the unified runtime execution of all 16 architecture phases working in harmony.</p>
 */
public class AgcFullEndToEndIntegrationTest {

    @Test
    public void testFullPhases0Through15Lifecycle() {
        System.out.println(">>> STARTING FULL AGC PHASES 0-15 INTEGRATION TEST <<<");

        // Phase 0: Topology & Telemetry
        AgcHardwareTopologyDetector.HardwareProfile profile = AgcHardwareTopologyDetector.get().profile();
        assertNotNull(profile);
        assertTrue(profile.logicalCores() >= 1);

        // Phase 1: Parallel World Engine & Bridge Lifecycle
        AgcHotPathRuntimeBridge bridge = AgcHotPathRuntimeBridge.get();
        bridge.onServerTickStart(1L);

        List<String> simWorlds = List.of("world_main", "world_nether", "world_the_end", "world_dungeon_1");
        AtomicInteger worldsTicked = new AtomicInteger(0);
        bridge.tickWorlds(
            simWorlds,
            w -> w,
            w -> w.equals("world_main") ? 10 : 0,
            w -> worldsTicked.incrementAndGet(),
            () -> true
        );
        assertEquals(4, worldsTicked.get());

        // Phase 2: Entity & Combat Sub-Tick
        UUID victim = UUID.randomUUID();
        AtomicBoolean kbDispatched = new AtomicBoolean(false);
        AgcSingleplayerFeelCombatEngine.getInstance().setEnabled(true);
        boolean kbOk = bridge.processCombatKnockback(
            victim, 0.4, 0.4, 0.4, 0.0, true, 1.0, 1, 0.0, () -> kbDispatched.set(true)
        );
        assertTrue(kbOk);
        assertTrue(kbDispatched.get());

        // Phase 3: Direct I/O Admission
        assertTrue(bridge.admitChunkOperation(16));

        // Phase 4: Network Broadcast Zero-Copy
        int recipients = bridge.broadcastZeroCopy(
            0x25, new byte[]{1, 2, 3, 4}, List.of("p1", "p2", "p3"), (p, b) -> {}
        );
        assertEquals(3, recipients);

        // Phase 5: Hibernation
        AgcWorldHibernationEngine.get().updateWorld("world_empty", 0, 1L, 5L); // Enters DRAINING
        AgcWorldHibernationEngine.WorldState hState =
            AgcWorldHibernationEngine.get().updateWorld("world_empty", 0, 10L, 5L); // Enters HIBERNATING
        assertEquals(AgcWorldHibernationEngine.WorldState.HIBERNATING, hState);

        // Phase 6: Memory & Self-Healing
        AgcSelfHealingEngine selfHealing = AgcSelfHealingEngine.get();
        int oomMitigations = selfHealing.checkAndMitigateOom(AgcMemoryTracker.get());
        assertTrue(oomMitigations >= 0);

        // Phase 7: Scheduled Ticks & Block Batching
        io.papermc.paper.agc.tick.AgcBlockTickBatcher batcher = io.papermc.paper.agc.tick.AgcBlockTickBatcher.get();
        batcher.scheduleTick(100L, 0, 0, 10, 64, 10, "stone");
        assertTrue(batcher.metrics().scheduledTicksEnqueued() >= 1);

        // Phase 8: Lock-Free Event Dispatcher
        io.papermc.paper.agc.event.AgcLockFreeEventDispatcher dispatcher = io.papermc.paper.agc.event.AgcLockFreeEventDispatcher.get();
        AtomicBoolean eventFired = new AtomicBoolean(false);
        dispatcher.registerListener(String.class, event -> eventFired.set(true));
        dispatcher.fireEvent("payload");
        assertTrue(eventFired.get());

        // Phase 9: Monitoring & Prometheus Exporter
        String prometheusMetrics = AgcPrometheusExporter.get().exportPrometheusMetrics();
        assertTrue(prometheusMetrics.contains("agc_server_tps"));
        assertTrue(prometheusMetrics.contains("agc_adaptive_throttle_level"));

        // Phase 10: Data Structures (Timing Wheel, Spatial Grid, Fast Hasher)
        AgcTimingWheel wheel = AgcTimingWheel.get();
        AtomicBoolean wheelRan = new AtomicBoolean(false);
        wheel.schedule(5, () -> wheelRan.set(true));
        for (int i = 0; i < 6; i++) {
            wheel.advanceTick();
        }
        assertTrue(wheelRan.get());

        AgcSpatialGrid grid = AgcSpatialGrid.get();
        grid.clear();
        grid.insert(999L, 10.0, 64.0, 10.0, "EntityData");
        List<Object> nearby = new ArrayList<>();
        int count = grid.queryRadius(10.0, 64.0, 10.0, 5.0, nearby);
        assertEquals(1, count);

        long hash = AgcFastHasher.get().hashCoords(10, 64, 20);
        assertTrue(hash != 0);

        // Phase 11: JIT, SIMD & Native Segment
        AgcTypeDispatcher.get().dispatch(AgcTypeDispatcher.TypeId.PLAYER, "PlayerHandle", p -> {});
        assertTrue(AgcTypeDispatcher.get().metrics().fastPathDispatches() >= 1);

        double[] distances = new double[1];
        AgcVectorMath.get().calculateBatchDistances(0, 0, 0, new double[]{3, 4, 0}, 1, distances);
        assertEquals(5.0, distances[0], 1.0e-5);

        double noise = AgcNativeAccelerator.get().fastNoise2D(5.0, 10.0);
        assertTrue(noise >= -2.0 && noise <= 2.0);

        // Phase 12: Villager, Spawner, Explosion
        AgcVillagerOptimizer.get().recordPoiOutcome(77L, 1L, true);
        assertTrue(AgcVillagerOptimizer.get().shouldThrottlePoiQuery(77L, 10L));

        assertTrue(AgcSpawnerOptimizer.get().canSpawnInChunk(101L, 5, 24));
        assertFalse(AgcSpawnerOptimizer.get().canSpawnInChunk(102L, 30, 24));

        var blast = AgcExplosionOptimizer.get().submitExplosion("world_main", 0, 64, 0, 4.0f);
        assertFalse(blast.wasCoalesced());

        // Phase 13: Async Save, Player Data & Region FD
        AgcPlayerDataOptimizer.get().markDirty(victim, AgcPlayerDataOptimizer.DIRTY_INVENTORY);
        assertTrue(AgcPlayerDataOptimizer.get().shouldSaveAndClearDirty(victim, false));

        var regHandle = AgcRegionFileManager.get().acquireRegionHandle("r.0.0.mca", () -> new AgcRegionFileManager.ManagedRegionHandle("r.0.0.mca"));
        assertNotNull(regHandle);
        assertFalse(regHandle.isClosed());

        // Phase 14: Commands, Scoreboard & Chat
        assertTrue(AgcCommandOptimizer.get().canExecuteTabComplete(victim));
        assertFalse(AgcCommandOptimizer.get().canExecuteTabComplete(victim)); // Cooldown throttled

        assertTrue(AgcScoreboardOptimizer.get().submitScoreUpdate("coins", "PlayerX", 500));
        assertFalse(AgcScoreboardOptimizer.get().submitScoreUpdate("coins", "PlayerX", 500)); // Redundant filtered

        List<String> msgs = new ArrayList<>();
        AgcChatOptimizer.get().broadcastSinglePass(
            "GlobalAnnouncement",
            s -> "{\"text\":\"" + s + "\"}",
            List.of("u1", "u2"),
            (u, json) -> msgs.add(u + "->" + json)
        );
        assertEquals(2, msgs.size());

        // Phase 15: WorldGen (Noise, Structure, Feature)
        double density = AgcNoiseOptimizer.get().getInterpolatedDensity(888L, 4, 64, 4, () -> new double[64]);
        assertEquals(0.0, density, 1.0e-5);

        var structLoc = AgcStructureOptimizer.get().getOrCreateStructureLocation("world_main", "village", 5, 5, () -> new AgcStructureOptimizer.StructureLocationEntry(80, 64, 80, true));
        assertNotNull(structLoc);

        assertTrue(AgcFeatureOptimizer.get().isValidHeight(100, -64, 320));
        assertFalse(AgcFeatureOptimizer.get().isValidHeight(400, -64, 320));

        // Phase 16: StarLight, Network Codec, Slab Allocator, Chunk L1/L2 & Hopper
        var lightOpt = io.papermc.paper.agc.light.AgcStarLightBatchOptimizer.get();
        byte[] pooledNibble = lightOpt.acquireNibbleArray();
        assertNotNull(pooledNibble);
        lightOpt.releaseNibbleArray(pooledNibble);

        var netCodec = io.papermc.paper.agc.network.AgcFastNetworkSerializationEngine.get();
        byte[] vBuf = new byte[16];
        int vLen = netCodec.writeVarInt(vBuf, 0, 255);
        assertEquals(2, vLen);

        int vlLen = netCodec.writeVarLong(vBuf, 0, 10000000000L);
        assertTrue(vlLen >= 5);
        int[] readLen = new int[1];
        long decodedVl = netCodec.readVarLong(vBuf, 0, readLen);
        assertEquals(10000000000L, decodedVl);
        assertEquals(vlLen, readLen[0]);

        var slabAlloc = io.papermc.paper.agc.memory.AgcOffHeapSlabAllocator.get();
        var slab = slabAlloc.acquire(1024);
        assertNotNull(slab);
        slabAlloc.release(slab);

        var chunkCache = io.papermc.paper.agc.chunk.AgcChunkCacheHierarchy.get();
        chunkCache.putL1("world_main", 1, 1, "ChunkRef");
        assertEquals("ChunkRef", chunkCache.getL1("world_main", 1, 1));

        // Finish Server Tick
        bridge.onServerTickEnd(5_000_000L, 1L); // 5ms tick

        System.out.println(">>> ALL 17 ARCHITECTURE PHASES (0-16) SUCCESSFULLY VALIDATED AND VERIFIED! <<<");
    }
}
