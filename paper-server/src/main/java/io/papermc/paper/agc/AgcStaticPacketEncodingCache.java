package io.papermc.paper.agc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — replay cache for the encoding of the shared registry synchronisation burst.
 *
 * <p>The configuration phase of every join sends the registry synchronisation burst: one
 * {@code ClientboundRegistryDataPacket} per registry plus a {@code ClientboundUpdateTagsPacket}.
 * {@code SynchronizeRegistriesTask} builds the burst once per (registry access, known-pack set) and hands
 * the same list to every joining connection, so the packet <b>instances</b> are shared for the whole
 * registry lifetime. What still ran per connection was the encode: {@code ProtocolInfo.codec().encode(...)}
 * walking the whole NBT tree again.</p>
 *
 * <p>Measured: a login-storm JFR profile (200 bots at 100 joins/s, 5,288 execution samples) attributed 256
 * samples (4.8% of the JVM) to {@code CompoundTag.writeNamedTag} reached from {@code PacketEncoder.encode}
 * — exactly this per-connection re-encoding of a payload that no longer depends on the connection. Live
 * counters after a 6,000-join login storm on the reference box: <b>32 captures</b> (encodes actually
 * performed) and <b>5,968 replayed encodes</b>.</p>
 *
 * <p>This cache captures the encoded bytes on the first encode of an allow-listed packet instance and
 * replays them for every later recipient. Because the reuse happens <b>inside {@code PacketEncoder}</b>,
 * the original packet instance still reaches every outbound plugin handler unchanged — this is the
 * plugin-transparent counterpart of {@code MeteusPreEncodedPacket}, which achieves a similar saving by
 * wrapping the packet and is therefore visible to outbound interceptors (and off by default for exactly
 * that reason). Only packets that are</p>
 * <ul>
 *   <li><b>immutable</b> after construction (so the captured bytes cannot go stale), and</li>
 *   <li><b>connection independent</b> (pure protocol data: no chat components, no locale, no
 *       per-connection rendering state)</li>
 * </ul>
 * <p>are allow-listed, and the instances must actually be shared between connections for the cache to hit.
 * Compression and encryption still run per connection downstream of the encoder, so the wire output stays
 * byte-for-byte identical to the uncached path.</p>
 *
 * <p>Entries are keyed on <b>object identity</b>, twice over: the identity of the {@code ProtocolInfo} that
 * produced the bytes, and the identity of the packet instance. Both are deliberate:</p>
 * <ul>
 *   <li>the protocol level exists because the encoded form is a function of the protocol version — the same
 *       packet instance may legitimately be encoded differently for a client that negotiated a different
 *       game version;</li>
 *   <li>{@link IdentityKey} exists because the cached packets are <b>records</b>
 *       ({@code ClientboundRegistryDataPacket}) whose {@code equals} deep-compares the whole registry NBT
 *       tree, so keying a hash map on them would pay the exact cost this cache removes — and would let a
 *       distinct but equal-content broadcast reuse another broadcast's bytes, which the contract forbids.</li>
 * </ul>
 *
 * <p><b>Why this does not cover the movement-packet broadcasts.</b> An attempt to apply the same policy to
 * the one-shot movement packets that {@code MeteusPreEncodedPacket} allow-lists was measured and reverted: a
 * 45 s dense-combat run at 200 bots produced 149,508 captures and <b>zero</b> replays, i.e. every movement
 * packet that reached the encoder had exactly one recipient, so the policy was pure overhead. The Meteus
 * wrapper, by contrast, reports millions of replays in the same scenario because it captures at the fanout
 * site, where the recipient count is known before dispatch. Whatever the encoder does see there is
 * single-recipient, so the lever belongs at the fanout site — and the wrapper is the only place that can
 * exploit it without a recipient count being passed down to the encoder.</p>
 */
public final class AgcStaticPacketEncodingCache {

    /** Safety bound: entries are dropped wholesale when a per-protocol map grows past this. */
    private static final int MAX_ENTRIES_PER_PROTOCOL = 256;
    /** Safety bound on how many distinct protocol infos we track (one per protocol state/direction). */
    private static final int MAX_PROTOCOLS = 32;

    /**
     * Identity-keyed map key. {@code ConcurrentHashMap} always compares through {@code equals}, so the
     * packet has to be wrapped in something whose {@code equals} is reference equality — which is exactly
     * what the cache contract needs, and is also what keeps the lookup O(1) instead of {@code List.equals}
     * over every registry entry.
     */
    private static final class IdentityKey {
        private Object target;
        private int hash;

        /** Mutable probe key, never retained by a map (see {@link #PROBE}). */
        private IdentityKey() {
        }

        private IdentityKey(final Object target) {
            this.target = target;
            this.hash = System.identityHashCode(target);
        }

        private IdentityKey bind(final Object newTarget) {
            this.target = newTarget;
            this.hash = System.identityHashCode(newTarget);
            return this;
        }

        @Override
        public boolean equals(final Object other) {
            return this == other || (other instanceof IdentityKey key && key.target == this.target);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    /**
     * Reusable lookup key, one per thread. {@code ConcurrentHashMap.get} only probes with the key and never
     * stores it, so a thread-private mutable key is safe there and removes one allocation per replayed
     * packet. Captures always allocate a fresh key, because {@code put} retains it.
     */
    private static final ThreadLocal<IdentityKey> PROBE = ThreadLocal.withInitial(IdentityKey::new);

    private static final ConcurrentHashMap<Object, ConcurrentHashMap<IdentityKey, byte[]>> CACHED =
        new ConcurrentHashMap<>();
    private static final AtomicLong CAPTURES = new AtomicLong();
    private static final AtomicLong REPLAYS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();

    /** Runtime kill switch, mirrors the other AGC packet-path fast paths. */
    private static volatile boolean enabled = true;

    private AgcStaticPacketEncodingCache() {
    }

    /** Enables or disables the cache without a restart (disabled means "always encode normally"). */
    public static void setEnabled(final boolean value) {
        enabled = value;
    }

    /** Whether the cache is allowed to capture and replay encodings. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether the encoding of this packet instance is identical for every recipient and cannot change after
     * construction. Only these packets may be captured and replayed.
     */
    public static boolean isRegistryCacheable(final Object packet) {
        return packet instanceof net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket
            || packet instanceof net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
    }

    /**
     * The single gate the encoder consults: whether {@code packet} may be replayed right now. Keeps the
     * capability check out of the encode body so the encoder needs no policy knowledge.
     */
    public static boolean isReplayable(final Object packet) {
        return enabled
            && isRegistryCacheable(packet)
            && AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.REGISTRY_ENCODING_CACHE);
    }

    /**
     * Returns the bytes captured for this exact packet instance under this protocol, or {@code null} when
     * this is the first recipient (the caller must encode normally and then call
     * {@link #capture(Object, Object, byte[], int, int)}).
     */
    public static byte[] replay(final Object protocolInfo, final Object packet) {
        final ConcurrentHashMap<IdentityKey, byte[]> byPacket = CACHED.get(protocolInfo);
        final byte[] cached = byPacket == null ? null : byPacket.get(PROBE.get().bind(packet));
        if (cached != null) {
            REPLAYS.incrementAndGet();
        } else {
            MISSES.incrementAndGet();
        }
        return cached;
    }

    /**
     * Stores the encoding of {@code packet} for the remaining recipients on this protocol.
     *
     * @param bytes the backing buffer the bytes were read from
     * @param start the index in {@code bytes} at which this packet's encoding begins
     * @param end   the index one past the last encoded byte
     */
    public static void capture(final Object protocolInfo, final Object packet, final byte[] bytes, final int start, final int end) {
        final int length = end - start;
        if (length <= 0 || start < 0 || end > bytes.length) {
            return;
        }
        ConcurrentHashMap<IdentityKey, byte[]> byPacket = CACHED.get(protocolInfo);
        if (byPacket == null) {
            if (CACHED.size() >= MAX_PROTOCOLS) {
                // Protocol states are bounded in practice; dropping everything is cheaper and simpler than
                // tracking which entries became unreachable.
                CACHED.clear();
            }
            final ConcurrentHashMap<IdentityKey, byte[]> created = new ConcurrentHashMap<>();
            final ConcurrentHashMap<IdentityKey, byte[]> existing = CACHED.putIfAbsent(protocolInfo, created);
            byPacket = existing != null ? existing : created;
        }
        if (byPacket.size() >= MAX_ENTRIES_PER_PROTOCOL) {
            // Registry bursts are rebuilt wholesale on a datapack reload; same reasoning as above. Any
            // dropped entry only costs a normal encode for its next recipient.
            byPacket.clear();
        }
        final byte[] copy = new byte[length];
        System.arraycopy(bytes, start, copy, 0, length);
        byPacket.put(new IdentityKey(packet), copy);
        CAPTURES.incrementAndGet();
    }

    /** Total captures (encode-once events). */
    public static long captures() {
        return CAPTURES.get();
    }

    /** Total replayed encodes (encodes skipped). */
    public static long replays() {
        return REPLAYS.get();
    }

    /** Total first-recipient encodes that had nothing to replay. */
    public static long misses() {
        return MISSES.get();
    }

    /** Number of cached encodings currently held (test/diagnostic hook). */
    public static int size() {
        int total = 0;
        for (final ConcurrentHashMap<IdentityKey, byte[]> byPacket : CACHED.values()) {
            total += byPacket.size();
        }
        return total;
    }

    /** Clears the cache. Counters are intentionally left alone so telemetry stays monotonic. */
    public static void clear() {
        CACHED.clear();
    }
}
