package io.papermc.paper.agc.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Dynamic Connection & Bandwidth QoS Manager.
 *
 * <p>Manages Netty event loop worker sizing, connection bandwidth quotas,
 * and idle connection power-saving profiles for 5,000+ concurrent players.</p>
 */
public final class AgcConnectionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcConnectionManager.class);
    private static final AgcConnectionManager INSTANCE = new AgcConnectionManager();

    public static final long DEFAULT_BANDWIDTH_QUOTA_BYTES_PER_TICK = 512 * 1024; // 512KB / tick
    public static final long BURST_BANDWIDTH_QUOTA_BYTES_PER_TICK = 2 * 1024 * 1024; // 2MB / tick

    private final ConcurrentHashMap<Object, ConnectionProfile> connectionProfiles = new ConcurrentHashMap<>();

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong qosThrottledPackets = new AtomicLong(0);
    private final AtomicLong totalBytesManaged = new AtomicLong(0);

    public static AgcConnectionManager get() {
        return INSTANCE;
    }

    private AgcConnectionManager() {}

    /**
     * Calculates the optimal Netty event loop worker count based on current CCU.
     *
     * @param concurrentUsers Number of active players
     * @return Recommended worker thread count
     */
    public int computeOptimalWorkerThreads(final int concurrentUsers) {
        if (concurrentUsers <= 500) {
            return 4;
        } else if (concurrentUsers <= 2000) {
            return 8;
        } else {
            return 16;
        }
    }

    /**
     * Registers a new connection for bandwidth tracking and QoS governance.
     */
    public void registerConnection(final Object connection) {
        if (connection == null) return;
        this.connectionProfiles.put(connection, new ConnectionProfile(System.currentTimeMillis()));
        this.activeConnections.incrementAndGet();
    }

    /**
     * Unregisters a disconnected channel.
     */
    public void unregisterConnection(final Object connection) {
        if (connection == null) return;
        if (this.connectionProfiles.remove(connection) != null) {
            this.activeConnections.decrementAndGet();
        }
    }

    /**
     * Checks whether an outbound packet can be dispatched under connection QoS limits.
     * High priority packets bypass throttling.
     *
     * @param connection Connection handle
     * @param packetBytes Outbound payload size in bytes
     * @param isHighPriority Whether the packet is critical (chat, combat, keep-alive)
     * @return {@code true} if transmission is permitted immediately
     */
    public boolean admitPacket(final Object connection, final int packetBytes, final boolean isHighPriority) {
        if (connection == null) return true;
        this.totalBytesManaged.addAndGet(packetBytes);

        if (isHighPriority) {
            final ConnectionProfile profile = this.connectionProfiles.get(connection);
            if (profile != null) {
                profile.lastPacketTimestamp = System.currentTimeMillis();
            }
            return true;
        }

        final ConnectionProfile profile = this.connectionProfiles.computeIfAbsent(
            connection, k -> new ConnectionProfile(System.currentTimeMillis())
        );

        profile.lastPacketTimestamp = System.currentTimeMillis();
        final long currentUsage = profile.bytesThisTick.addAndGet(packetBytes);

        if (currentUsage > DEFAULT_BANDWIDTH_QUOTA_BYTES_PER_TICK) {
            this.qosThrottledPackets.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Resets per-tick bandwidth usage counters across all connections.
     * Must be invoked at tick start.
     */
    public void onTickStart() {
        for (final ConnectionProfile profile : this.connectionProfiles.values()) {
            profile.bytesThisTick.set(0);
        }
    }

    /**
     * Returns whether a connection has been idle (no packet activity) for over the threshold.
     */
    public boolean isConnectionIdle(final Object connection, final long idleThresholdMs) {
        if (connection == null) return false;
        final ConnectionProfile profile = this.connectionProfiles.get(connection);
        if (profile == null) return false;
        return (System.currentTimeMillis() - profile.lastPacketTimestamp) > idleThresholdMs;
    }

    public void clear() {
        this.connectionProfiles.clear();
        this.activeConnections.set(0);
        this.qosThrottledPackets.set(0);
        this.totalBytesManaged.set(0);
    }

    public ConnectionManagerMetrics metrics() {
        return new ConnectionManagerMetrics(
            this.activeConnections.get(),
            computeOptimalWorkerThreads(this.activeConnections.get()),
            this.qosThrottledPackets.get(),
            this.totalBytesManaged.get()
        );
    }

    private static final class ConnectionProfile {
        private volatile long lastPacketTimestamp;
        private final AtomicLong bytesThisTick = new AtomicLong(0);

        private ConnectionProfile(final long lastPacketTimestamp) {
            this.lastPacketTimestamp = lastPacketTimestamp;
        }
    }

    public record ConnectionManagerMetrics(
        int activeConnections,
        int optimalWorkerThreads,
        long qosThrottledPackets,
        long totalBytesManaged
    ) {
    }
}
