package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgcHotPathRuntimeBridgeTest {

    private AgcHotPathRuntimeBridge bridge;

    @BeforeEach
    public void setup() {
        this.bridge = AgcHotPathRuntimeBridge.get();
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        AgcSingleplayerFeelCombatEngine.getInstance().setEnabled(true);
    }

    @Test
    public void testServerTickLifecycle() {
        final long initialTicks = this.bridge.metrics().totalServerTicks();
        this.bridge.onServerTickStart(100);
        this.bridge.onServerTickEnd(5_000_000L, 100); // 5.0ms tick

        final var m = this.bridge.metrics();
        assertTrue(m.totalServerTicks() > initialTicks);
    }

    @Test
    public void testWorldTickingWithHibernation() {
        record DummyWorld(String name, int players) {}
        final List<DummyWorld> worlds = List.of(
            new DummyWorld("active_world_1", 10),
            new DummyWorld("active_world_2", 5),
            new DummyWorld("hibernating_world_3", 0)
        );

        final AtomicInteger tickedCount = new AtomicInteger();
        this.bridge.tickWorlds(
            worlds,
            DummyWorld::name,
            DummyWorld::players,
            world -> tickedCount.incrementAndGet(),
            () -> true
        );

        assertTrue(tickedCount.get() >= 2, "Active worlds should be ticked");
    }

    @Test
    public void testCombatKnockbackDispatch() {
        final UUID victim = UUID.randomUUID();
        final AtomicInteger dispatched = new AtomicInteger();

        final boolean processed = this.bridge.processCombatKnockback(
            victim, 0.4, 0.4, 0.0, 0.5,
            true, 1.0, 0, 0.0,
            dispatched::incrementAndGet
        );

        assertTrue(processed, "Combat knockback should be processed by engine");
        assertEquals(1, dispatched.get(), "Sub-tick packet should be immediately dispatched");
    }

    @Test
    public void testSimdCollisionAndPhysicsSweep() {
        final float[] minX = new float[]{0f, 10f};
        final float[] minY = new float[]{0f, 10f};
        final float[] minZ = new float[]{0f, 10f};
        final float[] maxX = new float[]{1f, 11f};
        final float[] maxY = new float[]{1f, 11f};
        final float[] maxZ = new float[]{1f, 11f};
        final int[] collided = new int[2];

        final int count = this.bridge.sweepSimdCollisions(
            0.5f, 0.5f, 0.5f, 1.5f, 1.5f, 1.5f,
            minX, minY, minZ, maxX, maxY, maxZ, 2,
            collided
        );

        assertTrue(count >= 1, "Should detect at least 1 collision with box 0");

        final int idx = AgcSoaEntityPhysicsEngine.get().allocateEntity(
            0f, 10f, 0f,
            0f, 0f, 0f,
            0.3f, 1.8f,
            0.08f, 0.98f
        );
        this.bridge.stepSoaPhysics(1.0f);
        assertTrue(AgcSoaEntityPhysicsEngine.get().getY(idx) < 10.0f, "Entity should fall under gravity");
    }

    @Test
    public void testZeroCopyBroadcastAndDeltaTracking() {
        final List<String> subscribers = List.of("Player1", "Player2", "Player3");
        final AtomicInteger delivered = new AtomicInteger();

        final int reached = this.bridge.broadcastZeroCopy(
            0x26, new byte[]{1, 2, 3, 4}, subscribers,
            (sub, bytes) -> delivered.incrementAndGet()
        );

        assertEquals(3, reached);
        assertEquals(3, delivered.get());

        final var current = new AgcBitLevelDeltaEntityTracker.EntityState(1f, 2f, 3f, 0, 0, 0f, 0f, 0f, 20f, (byte) 0);
        final var previous = new AgcBitLevelDeltaEntityTracker.EntityState(1f, 2f, 3f, 0, 0, 0f, 0f, 0f, 20f, (byte) 0);
        final long mask = this.bridge.computeEntityDeltaMask(current, previous);
        assertEquals(0L, mask, "Identical states should have 0 dirty mask");
    }

    @Test
    public void testChunkQuotaAdmission() {
        final boolean admitted = this.bridge.admitChunkOperation(5);
        assertTrue(admitted, "Chunk quota should be admitted under normal token budget");
    }

    @Test
    public void testSubsystemFastPathHooks() {
        // Lighting hook
        final boolean queued = this.bridge.queueLightUpdate(10, 5, 20, true, false);
        assertFalse(queued);

        // Hopper hook
        final Object chest = new Object();
        final Object resolved = this.bridge.resolveTargetContainer(778899L, () -> chest);
        assertSame(chest, resolved);

        // Redstone hook
        assertTrue(this.bridge.filterRedundantRedstone(15, 15));
        assertFalse(this.bridge.filterRedundantRedstone(15, 14));

        // VarInt hook
        final byte[] buf = new byte[8];
        final int len = this.bridge.writeVarInt(buf, 0, 127);
        assertEquals(1, len);
        assertEquals(127, buf[0]);
    }

    @Test
    public void testChunkLifecycleAndBlockStateSync() {
        final Object chunkObj = new Object();
        this.bridge.onChunkLoaded("world", 10, 20, chunkObj);
        assertSame(chunkObj, io.papermc.paper.agc.chunk.AgcChunkCacheHierarchy.get().getL1("world", 10, 20));

        this.bridge.onBlockStateChanged("world", 10, 20, 5, 128, 1, 2);
        final var rcuSection = io.papermc.paper.agc.AgcLockFreeRcuChunkMap.get().getSection("world", 10, 20, 5);
        assertNotNull(rcuSection);
        assertEquals(2, rcuSection.getBlock(128));

        this.bridge.onChunkUnloaded("world", 10, 20);
        assertNull(io.papermc.paper.agc.chunk.AgcChunkCacheHierarchy.get().getL1("world", 10, 20));
        assertNull(io.papermc.paper.agc.AgcLockFreeRcuChunkMap.get().getSection("world", 10, 20, 5));
    }

    @Test
    public void testBroadcastPacketDeduplicated() {
        final List<String> subscribers = List.of("PlayerA", "PlayerB", "PlayerC");
        final AtomicInteger delivered = new AtomicInteger();

        this.bridge.broadcastPacketDeduplicated("DummyPacket", subscribers, p -> delivered.incrementAndGet());
        assertEquals(3, delivered.get());
    }
}
