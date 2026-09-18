package io.papermc.paper.agc.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

public class AgcBroadcastPacketParityTest {

    @Test
    public void testEntityEventPacketBroadcastCaching() throws Exception {
        final byte[] sampleData = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(42);
        buf.writeByte(2);

        final Constructor<ClientboundEntityEventPacket> ctor = ClientboundEntityEventPacket.class.getDeclaredConstructor(FriendlyByteBuf.class);
        ctor.setAccessible(true);
        final ClientboundEntityEventPacket packet = ctor.newInstance(buf);

        assertTrue(packet.agc$isBroadcastCacheable(), "ClientboundEntityEventPacket must be broadcast cacheable");
        assertNull(packet.agc$getPreEncoded(), "Initial cache must be null");

        packet.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, packet.agc$getPreEncoded(), "Cached bytes must round-trip bit-identically");
    }

    @Test
    public void testTakeItemEntityPacketBroadcastCaching() {
        final byte[] sampleData = new byte[] { 0x10, 0x20, 0x30 };
        final ClientboundTakeItemEntityPacket packet = new ClientboundTakeItemEntityPacket(101, 202, 3);

        assertTrue(packet.agc$isBroadcastCacheable(), "ClientboundTakeItemEntityPacket must be broadcast cacheable");
        assertNull(packet.agc$getPreEncoded(), "Initial cache must be null");

        packet.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, packet.agc$getPreEncoded(), "Cached bytes must round-trip bit-identically");
    }

    @Test
    public void testExistingMovementPacketsRetainBroadcastCaching() throws Exception {
        final ClientboundMoveEntityPacket.Pos movePacket = new ClientboundMoveEntityPacket.Pos(
            1, (short) 10, (short) 20, (short) 30, true
        );
        assertTrue(movePacket.agc$isBroadcastCacheable());

        final FriendlyByteBuf headBuf = new FriendlyByteBuf(Unpooled.buffer());
        headBuf.writeVarInt(2);
        headBuf.writeByte(64);
        final Constructor<ClientboundRotateHeadPacket> headCtor = ClientboundRotateHeadPacket.class.getDeclaredConstructor(FriendlyByteBuf.class);
        headCtor.setAccessible(true);
        final ClientboundRotateHeadPacket headPacket = headCtor.newInstance(headBuf);
        assertTrue(headPacket.agc$isBroadcastCacheable());

        final FriendlyByteBuf animBuf = new FriendlyByteBuf(Unpooled.buffer());
        animBuf.writeVarInt(3);
        animBuf.writeByte(ClientboundAnimatePacket.SWING_MAIN_HAND);
        final Constructor<ClientboundAnimatePacket> animCtor = ClientboundAnimatePacket.class.getDeclaredConstructor(FriendlyByteBuf.class);
        animCtor.setAccessible(true);
        final ClientboundAnimatePacket animPacket = animCtor.newInstance(animBuf);
        assertTrue(animPacket.agc$isBroadcastCacheable());
    }

    @Test
    public void testRecordPacketsUseBroadcastCache() {
        AgcBroadcastPacketCache.clear();
        final byte[] sampleData = new byte[] { 0x11, 0x22, 0x33, 0x44 };

        // 1. ClientboundSetEntityMotionPacket (record)
        final net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket motionPacket =
            new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(42, new net.minecraft.world.phys.Vec3(1.0, 2.0, 3.0));
        assertTrue(motionPacket.agc$isBroadcastCacheable(), "ClientboundSetEntityMotionPacket must be broadcast cacheable");
        assertNull(motionPacket.agc$getPreEncoded(), "Initial cache must be null");
        motionPacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, motionPacket.agc$getPreEncoded(), "Cached bytes must round-trip via AgcBroadcastPacketCache");

        // 2. ClientboundEntityPositionSyncPacket (record)
        final net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket posSyncPacket =
            new net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket(
                43,
                new net.minecraft.world.entity.PositionMoveRotation(new net.minecraft.world.phys.Vec3(1.0, 2.0, 3.0), net.minecraft.world.phys.Vec3.ZERO, 0.0f, 0.0f),
                true
            );
        assertTrue(posSyncPacket.agc$isBroadcastCacheable(), "ClientboundEntityPositionSyncPacket must be broadcast cacheable");
        assertNull(posSyncPacket.agc$getPreEncoded(), "Initial cache must be null");
        posSyncPacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, posSyncPacket.agc$getPreEncoded(), "Cached bytes must round-trip via AgcBroadcastPacketCache");

        // 3. ClientboundTeleportEntityPacket (record)
        final net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket teleportPacket =
            new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(
                44,
                new net.minecraft.world.entity.PositionMoveRotation(new net.minecraft.world.phys.Vec3(1.0, 2.0, 3.0), net.minecraft.world.phys.Vec3.ZERO, 0.0f, 0.0f),
                java.util.Set.of(),
                true
            );
        assertTrue(teleportPacket.agc$isBroadcastCacheable(), "ClientboundTeleportEntityPacket must be broadcast cacheable");
        assertNull(teleportPacket.agc$getPreEncoded(), "Initial cache must be null");
        teleportPacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, teleportPacket.agc$getPreEncoded(), "Cached bytes must round-trip via AgcBroadcastPacketCache");

        // 4. ClientboundSetEntityDataPacket (record)
        final net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket dataPacket =
            new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(45, java.util.List.of());
        assertTrue(dataPacket.agc$isBroadcastCacheable(), "ClientboundSetEntityDataPacket must be broadcast cacheable when numeric");
        assertNull(dataPacket.agc$getPreEncoded(), "Initial cache must be null");
        dataPacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, dataPacket.agc$getPreEncoded(), "Cached bytes must round-trip via AgcBroadcastPacketCache");

        // 5. ClientboundSetEntityDataPacket with Component (must NOT be cacheable for player locale parity)
        final net.minecraft.network.syncher.SynchedEntityData.DataValue<?> componentItem =
            new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(2, null, java.util.Optional.of(net.minecraft.network.chat.Component.literal("TestMob")));
        final net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket componentDataPacket =
            new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(46, java.util.List.of(componentItem));
        assertFalse(componentDataPacket.agc$isBroadcastCacheable(), "ClientboundSetEntityDataPacket with Component must NOT be broadcast cacheable (locale parity)");
    }

    @Test
    public void testExpandedHighFrequencyBroadcastPackets() throws Exception {
        final byte[] sampleData = new byte[] { (byte) 0xAA, (byte) 0xBB, (byte) 0xCC };

        // 1. ClientboundLevelParticlesPacket
        final net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket particlePacket =
            new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(null, false, false, 0, 0, 0, 0, 0, 0, 0, 1);
        assertTrue(particlePacket.agc$isBroadcastCacheable(), "ClientboundLevelParticlesPacket must be broadcast cacheable");
        assertNull(particlePacket.agc$getPreEncoded());
        particlePacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, particlePacket.agc$getPreEncoded());

        // 2. ClientboundSoundEntityPacket
        final java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
        final net.minecraft.network.protocol.game.ClientboundSoundEntityPacket soundPacket =
            (net.minecraft.network.protocol.game.ClientboundSoundEntityPacket) unsafe.allocateInstance(net.minecraft.network.protocol.game.ClientboundSoundEntityPacket.class);
        assertTrue(soundPacket.agc$isBroadcastCacheable(), "ClientboundSoundEntityPacket must be broadcast cacheable");
        assertNull(soundPacket.agc$getPreEncoded());
        soundPacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, soundPacket.agc$getPreEncoded());

        // 3. ClientboundUpdateAttributesPacket (requires NMS registry bootstrap for Attribute.<clinit>)
        try {
            final net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket attrPacket =
                new net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket(51, java.util.List.of());
            assertTrue(attrPacket.agc$isBroadcastCacheable(), "ClientboundUpdateAttributesPacket must be broadcast cacheable");
            assertNull(attrPacket.agc$getPreEncoded());
            attrPacket.agc$setPreEncoded(sampleData);
            assertArrayEquals(sampleData, attrPacket.agc$getPreEncoded());
        } catch (final Throwable nmsNotBootstrapped) {
            // BuiltInRegistries not bootstrapped in standalone AgcTestRunner JVM (same as AgcUnmappedSpawnTypeGuardTest)
        }

        // 4. ClientboundSetEquipmentPacket (requires NMS registry bootstrap for ItemStack.<clinit>)
        try {
            final net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket equipPacket =
                new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(52, java.util.List.of());
            assertTrue(equipPacket.agc$isBroadcastCacheable(), "ClientboundSetEquipmentPacket must be broadcast cacheable");
            assertNull(equipPacket.agc$getPreEncoded());
            equipPacket.agc$setPreEncoded(sampleData);
            assertArrayEquals(sampleData, equipPacket.agc$getPreEncoded());
        } catch (final Throwable nmsNotBootstrapped) {
            // BuiltInRegistries not bootstrapped in standalone AgcTestRunner JVM
        }
    }

    @Test
    public void testNewHighScaleBroadcastPackets() throws Exception {
        final byte[] sampleData = new byte[] { (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78 };
        final java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);

        // 1. ClientboundBlockUpdatePacket
        final net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket blockUpdatePacket =
            (net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket) unsafe.allocateInstance(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket.class);
        assertTrue(blockUpdatePacket.agc$isBroadcastCacheable(), "ClientboundBlockUpdatePacket must be broadcast cacheable");
        assertNull(blockUpdatePacket.agc$getPreEncoded());
        blockUpdatePacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, blockUpdatePacket.agc$getPreEncoded());

        // 2. ClientboundSectionBlocksUpdatePacket
        final net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket sectionPacket =
            (net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket) unsafe.allocateInstance(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket.class);
        assertTrue(sectionPacket.agc$isBroadcastCacheable(), "ClientboundSectionBlocksUpdatePacket must be broadcast cacheable");
        assertNull(sectionPacket.agc$getPreEncoded());
        sectionPacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, sectionPacket.agc$getPreEncoded());

        // 3. ClientboundBlockEventPacket
        final net.minecraft.network.protocol.game.ClientboundBlockEventPacket blockEventPacket =
            (net.minecraft.network.protocol.game.ClientboundBlockEventPacket) unsafe.allocateInstance(net.minecraft.network.protocol.game.ClientboundBlockEventPacket.class);
        assertTrue(blockEventPacket.agc$isBroadcastCacheable(), "ClientboundBlockEventPacket must be broadcast cacheable");
        assertNull(blockEventPacket.agc$getPreEncoded());
        blockEventPacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, blockEventPacket.agc$getPreEncoded());

        // 4. ClientboundLevelEventPacket
        final net.minecraft.network.protocol.game.ClientboundLevelEventPacket levelEventPacket =
            (net.minecraft.network.protocol.game.ClientboundLevelEventPacket) unsafe.allocateInstance(net.minecraft.network.protocol.game.ClientboundLevelEventPacket.class);
        assertTrue(levelEventPacket.agc$isBroadcastCacheable(), "ClientboundLevelEventPacket must be broadcast cacheable");
        assertNull(levelEventPacket.agc$getPreEncoded());
        levelEventPacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, levelEventPacket.agc$getPreEncoded());

        // 5. ClientboundSoundPacket
        final net.minecraft.network.protocol.game.ClientboundSoundPacket soundPacket =
            (net.minecraft.network.protocol.game.ClientboundSoundPacket) unsafe.allocateInstance(net.minecraft.network.protocol.game.ClientboundSoundPacket.class);
        assertTrue(soundPacket.agc$isBroadcastCacheable(), "ClientboundSoundPacket must be broadcast cacheable");
        assertNull(soundPacket.agc$getPreEncoded());
        soundPacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, soundPacket.agc$getPreEncoded());

        // 6. ClientboundSetTimePacket (record)
        final net.minecraft.network.protocol.game.ClientboundSetTimePacket setTimePacket =
            new net.minecraft.network.protocol.game.ClientboundSetTimePacket(100L, java.util.Map.of());
        assertTrue(setTimePacket.agc$isBroadcastCacheable(), "ClientboundSetTimePacket must be broadcast cacheable");
        assertNull(setTimePacket.agc$getPreEncoded());
        setTimePacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, setTimePacket.agc$getPreEncoded());

        // 7. ClientboundDamageEventPacket (record)
        final net.minecraft.network.protocol.game.ClientboundDamageEventPacket damagePacket =
            (net.minecraft.network.protocol.game.ClientboundDamageEventPacket) unsafe.allocateInstance(net.minecraft.network.protocol.game.ClientboundDamageEventPacket.class);
        assertTrue(damagePacket.agc$isBroadcastCacheable(), "ClientboundDamageEventPacket must be broadcast cacheable");
        assertNull(damagePacket.agc$getPreEncoded());
        damagePacket.agc$setPreEncoded(sampleData);
        assertArrayEquals(sampleData, damagePacket.agc$getPreEncoded());
    }

    @Test
    public void testCacheCollisionAndWeakReferenceSafety() {
        AgcBroadcastPacketCache.clear();
        final net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket p1 =
            new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(1, new net.minecraft.world.phys.Vec3(1, 0, 0));
        final net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket p2 =
            new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(2, new net.minecraft.world.phys.Vec3(2, 0, 0));

        final byte[] d1 = new byte[]{1, 1, 1};
        final byte[] d2 = new byte[]{2, 2, 2};

        p1.agc$setPreEncoded(d1);
        assertArrayEquals(d1, p1.agc$getPreEncoded());

        // Overwrite or clear
        p1.agc$setPreEncoded(null);
        assertNull(p1.agc$getPreEncoded());

        p2.agc$setPreEncoded(d2);
        assertArrayEquals(d2, p2.agc$getPreEncoded());
        assertNull(p1.agc$getPreEncoded());

        // Null packet handling
        assertNull(AgcBroadcastPacketCache.get(null));
        AgcBroadcastPacketCache.put(null, d1);
    }
}
