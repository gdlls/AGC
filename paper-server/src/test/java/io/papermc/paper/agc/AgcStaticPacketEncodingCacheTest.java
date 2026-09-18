package io.papermc.paper.agc;

import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link AgcStaticPacketEncodingCache}: only the shared, connection-independent
 * registry-burst packets are cacheable, the bytes are captured once and replayed per protocol, and
 * distinct packet instances or distinct protocols never share bytes.
 */
class AgcStaticPacketEncodingCacheTest {

    private static final Object PROTOCOL_A = new Object();
    private static final Object PROTOCOL_B = new Object();

    private static ClientboundUpdateTagsPacket tagsPacket() {
        return new ClientboundUpdateTagsPacket(Map.of());
    }

    private static ClientboundRegistryDataPacket registryPacket() {
        return new ClientboundRegistryDataPacket(
            ResourceKey.createRegistryKey(Identifier.parse("minecraft:agc_test_registry")),
            List.of()
        );
    }

    private static ClientboundMoveEntityPacket movePacket() {
        return new ClientboundMoveEntityPacket.Pos(1, (short) 0, (short) 0, (short) 0, true);
    }

    // The cache holds JVM-wide state and its counters are intentionally monotonic across clears (they are
    // live telemetry), so every assertion below is expressed as a delta or a shape rather than an absolute
    // value: the JUnit method order inside one JVM run is not guaranteed.
    @BeforeEach
    void resetCache() {
        AgcStaticPacketEncodingCache.clear();
        AgcStaticPacketEncodingCache.setEnabled(true);
    }

    @AfterEach
    void restoreCache() {
        AgcStaticPacketEncodingCache.clear();
        AgcStaticPacketEncodingCache.setEnabled(true);
    }

    @Test
    void onlyTheSharedRegistryBurstIsCacheable() {
        assertTrue(AgcStaticPacketEncodingCache.isRegistryCacheable(tagsPacket()),
            "the tag sync packet is shared between connections and immutable");
        assertTrue(AgcStaticPacketEncodingCache.isRegistryCacheable(registryPacket()),
            "the registry data packet is shared between connections and immutable");

        assertFalse(AgcStaticPacketEncodingCache.isRegistryCacheable("not a packet"),
            "arbitrary objects must never be put into the cache");
        assertFalse(AgcStaticPacketEncodingCache.isRegistryCacheable(new Object()));
        assertFalse(AgcStaticPacketEncodingCache.isRegistryCacheable(movePacket()),
            "one-shot entity movement packets are single-recipient at the encoder and must not be cached");
    }

    @Test
    void firstRecipientMissesThenCaptureMakesReplayAvailable() {
        final Object packet = tagsPacket();
        final byte[] bytes = {1, 2, 3, 4};
        final long missesBefore = AgcStaticPacketEncodingCache.misses();
        final long replaysBefore = AgcStaticPacketEncodingCache.replays();
        final long capturesBefore = AgcStaticPacketEncodingCache.captures();

        assertNull(AgcStaticPacketEncodingCache.replay(PROTOCOL_A, packet),
            "the first recipient must encode normally");
        assertEquals(missesBefore + 1, AgcStaticPacketEncodingCache.misses());

        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, packet, bytes, 0, bytes.length);
        assertEquals(1, AgcStaticPacketEncodingCache.size());
        assertEquals(capturesBefore + 1, AgcStaticPacketEncodingCache.captures());

        final byte[] replayed = AgcStaticPacketEncodingCache.replay(PROTOCOL_A, packet);
        assertArrayEquals(bytes, replayed, "later recipients get exactly the captured bytes");
        assertEquals(replaysBefore + 1, AgcStaticPacketEncodingCache.replays());
    }

    @Test
    void onlyTheRequestedRangeIsCaptured() {
        final Object packet = registryPacket();
        final byte[] buffer = {9, 9, 7, 8, 9};

        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, packet, buffer, 2, 4);

        assertArrayEquals(new byte[]{7, 8}, AgcStaticPacketEncodingCache.replay(PROTOCOL_A, packet),
            "capture must copy only [start, end) out of the shared buffer");
    }

    @Test
    void captureIsIndependentOfTheSourceBuffer() {
        final Object packet = tagsPacket();
        final byte[] buffer = {1, 2, 3};

        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, packet, buffer, 0, buffer.length);
        buffer[0] = 42; // the source buffer is reused by the next encode
        buffer[1] = 43;
        buffer[2] = 44;

        assertArrayEquals(new byte[]{1, 2, 3}, AgcStaticPacketEncodingCache.replay(PROTOCOL_A, packet),
            "the cached encoding must be a defensive copy, not a view of the pooled buffer");
    }

    @Test
    void distinctInstancesNeverShareBytes() {
        final Object first = tagsPacket();
        final Object second = tagsPacket(); // equal content, different broadcast

        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, first, new byte[]{1}, 0, 1);

        assertArrayEquals(new byte[]{1}, AgcStaticPacketEncodingCache.replay(PROTOCOL_A, first));
        assertNull(AgcStaticPacketEncodingCache.replay(PROTOCOL_A, second),
            "an equal-content but distinct packet instance must not reuse another instance's bytes");
    }

    @Test
    void recordPacketsAreKeyedOnIdentityNotValueEquality() {
        // ClientboundRegistryDataPacket is a record, so two separately constructed instances with the same
        // content are equal(). The cache must still treat them as distinct broadcasts: keying on equals
        // would deep-compare the whole registry NBT tree on every lookup (the cost this cache exists to
        // remove) and would let one broadcast replay another's bytes.
        final Object first = registryPacket();
        final Object second = registryPacket();
        assertEquals(first, second, "the fixture must actually produce value-equal records");

        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, first, new byte[]{7}, 0, 1);
        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, second, new byte[]{9}, 0, 1);

        assertEquals(2, AgcStaticPacketEncodingCache.size(),
            "value-equal but distinct instances must occupy distinct cache entries");
        assertArrayEquals(new byte[]{7}, AgcStaticPacketEncodingCache.replay(PROTOCOL_A, first));
        assertArrayEquals(new byte[]{9}, AgcStaticPacketEncodingCache.replay(PROTOCOL_A, second));
    }

    @Test
    void protocolIdentityIsPartOfTheCacheKey() {
        final Object packet = tagsPacket();

        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, packet, new byte[]{1}, 0, 1);

        assertNull(AgcStaticPacketEncodingCache.replay(PROTOCOL_B, packet),
            "the encoded form depends on the protocol version, so entries must not be shared across protocols");
        assertArrayEquals(new byte[]{1}, AgcStaticPacketEncodingCache.replay(PROTOCOL_A, packet),
            "the original protocol's entry is unaffected");
    }

    @Test
    void emptyAndOutOfRangeCapturesAreIgnored() {
        final Object packet = tagsPacket();

        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, packet, new byte[0], 0, 0);
        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, packet, new byte[]{1}, 1, 1);
        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, packet, new byte[]{1}, -1, 1);
        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, packet, new byte[]{1}, 0, 5);

        assertEquals(0, AgcStaticPacketEncodingCache.size());
        assertNull(AgcStaticPacketEncodingCache.replay(PROTOCOL_A, packet));
    }

    @Test
    void clearDropsEntriesButKeepsCountersMonotonic() {
        final long capturesBefore = AgcStaticPacketEncodingCache.captures();
        AgcStaticPacketEncodingCache.capture(PROTOCOL_A, tagsPacket(), new byte[]{1}, 0, 1);
        assertEquals(capturesBefore + 1, AgcStaticPacketEncodingCache.captures());
        assertEquals(1, AgcStaticPacketEncodingCache.size());

        AgcStaticPacketEncodingCache.clear();

        assertEquals(0, AgcStaticPacketEncodingCache.size());
        assertEquals(capturesBefore + 1, AgcStaticPacketEncodingCache.captures(),
            "telemetry counters are monotonic and must survive a cache clear");
    }

    @Test
    void enabledFlagRoundTrips() {
        final boolean original = AgcStaticPacketEncodingCache.isEnabled();
        try {
            AgcStaticPacketEncodingCache.setEnabled(false);
            assertFalse(AgcStaticPacketEncodingCache.isEnabled(),
                "the kill switch must be observable without a restart");
            AgcStaticPacketEncodingCache.setEnabled(true);
            assertTrue(AgcStaticPacketEncodingCache.isEnabled());
        } finally {
            AgcStaticPacketEncodingCache.setEnabled(original);
        }
    }

    @Test
    void disabledCacheIsNeverReplayableWhateverTheMode() {
        final Object packet = tagsPacket();
        try {
            AgcStaticPacketEncodingCache.setEnabled(false);
            assertFalse(AgcStaticPacketEncodingCache.isReplayable(packet),
                "the kill switch must override the capability gate");
        } finally {
            AgcStaticPacketEncodingCache.setEnabled(true);
        }
    }
}
