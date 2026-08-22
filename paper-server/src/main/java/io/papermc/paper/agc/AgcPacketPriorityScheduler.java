package io.papermc.paper.agc;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Performance Packet Priority Scheduler.
 *
 * <p>Categorizes outbound Minecraft packets into prioritized traffic classes to guarantee
 * that latency-sensitive packets (Combat, Chat, KeepAlive, PlayerMove) are never starved or delayed
 * by heavy background data packets (Chunk serialization, Map data, Light updates, TabList).</p>
 */
public final class AgcPacketPriorityScheduler {

    private static final AgcPacketPriorityScheduler INSTANCE = new AgcPacketPriorityScheduler();

    public enum Priority {
        /** Critical network signals: KeepAlive, Ping, Disconnect. Always immediate flush. */
        CRITICAL(0, true),
        /** Interactive player actions: Chat, Combat, Block Break, Inventory. */
        INTERACTIVE(1, true),
        /** Standard world feedback: Entity Spawn/Move, Block Changes, Sounds, Particles. */
        STANDARD(2, false),
        /** Bulk background data: Chunk Data, Light Updates, Map Data, Full Metadata. */
        BULK_DATA(3, false);

        private final int level;
        private final boolean requiresImmediateFlush;

        Priority(final int level, final boolean requiresImmediateFlush) {
            this.level = level;
            this.requiresImmediateFlush = requiresImmediateFlush;
        }

        public int level() {
            return this.level;
        }

        public boolean requiresImmediateFlush() {
            return this.requiresImmediateFlush;
        }
    }

    // Per-priority packet counters
    private final AtomicLong criticalPackets = new AtomicLong();
    private final AtomicLong interactivePackets = new AtomicLong();
    private final AtomicLong standardPackets = new AtomicLong();
    private final AtomicLong bulkPackets = new AtomicLong();

    private final AtomicLong criticalBytes = new AtomicLong();
    private final AtomicLong interactiveBytes = new AtomicLong();
    private final AtomicLong standardBytes = new AtomicLong();
    private final AtomicLong bulkBytes = new AtomicLong();

    public static AgcPacketPriorityScheduler get() {
        return INSTANCE;
    }

    private AgcPacketPriorityScheduler() {}

    /**
     * Records a packet dispatch under a given priority class.
     *
     * @param priority  Priority tier
     * @param byteCount Estimated payload size in bytes
     */
    public void recordPacket(final Priority priority, final int byteCount) {
        if (priority == null) {
            return;
        }
        final long bytes = Math.max(0, byteCount);
        switch (priority) {
            case CRITICAL -> {
                this.criticalPackets.incrementAndGet();
                this.criticalBytes.addAndGet(bytes);
            }
            case INTERACTIVE -> {
                this.interactivePackets.incrementAndGet();
                this.interactiveBytes.addAndGet(bytes);
            }
            case STANDARD -> {
                this.standardPackets.incrementAndGet();
                this.standardBytes.addAndGet(bytes);
            }
            case BULK_DATA -> {
                this.bulkPackets.incrementAndGet();
                this.bulkBytes.addAndGet(bytes);
            }
        }
    }

    /**
     * Categorizes a packet class or packet name into an AGC Priority tier.
     *
     * @param packetClassName Simple or full class name of the packet
     * @return Resolved {@link Priority}
     */
    public Priority classify(final String packetClassName) {
        if (packetClassName == null || packetClassName.isEmpty()) {
            return Priority.STANDARD;
        }

        final String name = packetClassName.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("keepalive") || name.contains("disconnect") || name.contains("ping") || name.contains("login")) {
            return Priority.CRITICAL;
        }
        if (name.contains("chat") || name.contains("damage") || name.contains("hurt") || name.contains("move") || name.contains("interact") || name.contains("animate")) {
            return Priority.INTERACTIVE;
        }
        if (name.contains("chunk") || name.contains("light") || name.contains("map") || name.contains("tablist") || name.contains("recipe") || name.contains("metadata")) {
            return Priority.BULK_DATA;
        }
        return Priority.STANDARD;
    }

    /**
     * Resets all scheduler traffic counters.
     */
    public void resetMetrics() {
        this.criticalPackets.set(0);
        this.interactivePackets.set(0);
        this.standardPackets.set(0);
        this.bulkPackets.set(0);
        this.criticalBytes.set(0);
        this.interactiveBytes.set(0);
        this.standardBytes.set(0);
        this.bulkBytes.set(0);
    }

    /**
     * Returns a snapshot of priority scheduler traffic.
     */
    public SchedulerMetrics metrics() {
        return new SchedulerMetrics(
            this.criticalPackets.get(),
            this.interactivePackets.get(),
            this.standardPackets.get(),
            this.bulkPackets.get(),
            this.criticalBytes.get(),
            this.interactiveBytes.get(),
            this.standardBytes.get(),
            this.bulkBytes.get()
        );
    }

    public record SchedulerMetrics(
        long criticalPackets,
        long interactivePackets,
        long standardPackets,
        long bulkPackets,
        long criticalBytes,
        long interactiveBytes,
        long standardBytes,
        long bulkBytes
    ) {
        public long totalPackets() {
            return this.criticalPackets + this.interactivePackets + this.standardPackets + this.bulkPackets;
        }

        public long totalBytes() {
            return this.criticalBytes + this.interactiveBytes + this.standardBytes + this.bulkBytes;
        }
    }
}
