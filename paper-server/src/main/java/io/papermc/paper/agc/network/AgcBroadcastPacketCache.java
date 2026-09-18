package io.papermc.paper.agc.network;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.network.protocol.Packet;
import org.jspecify.annotations.Nullable;

/**
 * AGC - Lock-free direct-mapped reference cache for broadcast packets.
 * Enables arbitrary packet types (including records which cannot hold mutable instance fields)
 * to benefit from single-serialization broadcast replay across 1,000+ connected clients
 * with zero heap retention leaks (weak references) and uniform avalanche distribution.
 */
public final class AgcBroadcastPacketCache {

    private static final int SIZE = 16384;
    private static final int MASK = SIZE - 1;

    private record Entry(WeakReference<Packet<?>> packetRef, byte[] encoded) {}

    private static final AtomicReferenceArray<Entry> CACHE = new AtomicReferenceArray<>(SIZE);

    private AgcBroadcastPacketCache() {
    }

    private static int mix(int h) {
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    public static byte @Nullable [] get(final Packet<?> packet) {
        if (packet == null) {
            return null;
        }
        final int index = mix(System.identityHashCode(packet)) & MASK;
        final Entry entry = CACHE.get(index);
        if (entry != null && entry.packetRef.get() == packet) {
            return entry.encoded;
        }
        return null;
    }

    public static void put(final Packet<?> packet, final byte @Nullable [] encoded) {
        if (packet == null) {
            return;
        }
        final int index = mix(System.identityHashCode(packet)) & MASK;
        if (encoded == null) {
            final Entry entry = CACHE.get(index);
            if (entry != null && entry.packetRef.get() == packet) {
                CACHE.compareAndSet(index, entry, null);
            }
            return;
        }
        CACHE.set(index, new Entry(new WeakReference<>(packet), encoded));
    }

    public static void clear() {
        for (int i = 0; i < SIZE; i++) {
            CACHE.set(i, null);
        }
    }
}
