package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** FIFO-compatible chunk frontier scheduler for alpha23. */
public final class AGCChunkFrontierScheduler {
    public static final AGCChunkFrontierScheduler INSTANCE = new AGCChunkFrontierScheduler();

    private final EnumMap<AGCChunkBudget.Operation, AtomicLong> scheduled = new EnumMap<>(AGCChunkBudget.Operation.class);
    private final EnumMap<AGCChunkBudget.Operation, AtomicLong> waiting = new EnumMap<>(AGCChunkBudget.Operation.class);
    private volatile long tickSequence;
    private volatile long baseFrontierTokens = 262_144L;
    private volatile long remainingFrontierTokens = 262_144L;
    private volatile String lastReason = "cold";

    private AGCChunkFrontierScheduler() {
        for (final AGCChunkBudget.Operation op : AGCChunkBudget.Operation.values()) {
            this.scheduled.put(op, new AtomicLong());
            this.waiting.put(op, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.baseFrontierTokens = Math.max(65_536L, processors * 96_000L);
        this.remainingFrontierTokens = this.baseFrontierTokens;
        this.lastReason = "reset processors=" + processors;
    }

    public Frontier schedule(
        final UUID playerId,
        final AGCChunkBudget.Operation operation,
        final int forecastRadius,
        final int clusterSize,
        final String reason
    ) {
        final AGCChunkBudget.Operation op = operation == null ? AGCChunkBudget.Operation.LOAD : operation;
        final int radius = Math.max(1, forecastRadius);
        final int cluster = Math.max(1, clusterSize);
        final int rings = Math.max(1, Math.min(96, radius + (cluster / 64)));
        final int width = Math.max(1, Math.min(256, rings * 4));
        final long cost = Math.max(1L, (long) width * Math.max(1, cluster / 8));
        if (cost > this.remainingFrontierTokens) {
            this.waiting.get(op).incrementAndGet();
            this.lastReason = "frontier waits FIFO; no chunk head skip: " + safe(reason);
            return new Frontier(false, op, rings, width, cost, this.remainingFrontierTokens, stableKey(playerId, op, radius), this.lastReason);
        }
        this.remainingFrontierTokens -= cost;
        this.scheduled.get(op).incrementAndGet();
        this.lastReason = "scheduled FIFO-compatible chunk frontier op=" + op + " rings=" + rings + " width=" + width;
        return new Frontier(true, op, rings, width, cost, this.remainingFrontierTokens, stableKey(playerId, op, radius), this.lastReason);
    }

    public String statusLine() {
        return "AGCChunkFrontierScheduler{tick=" + this.tickSequence
            + ", baseTokens=" + this.baseFrontierTokens
            + ", remainingTokens=" + this.remainingFrontierTokens
            + ", scheduled=" + snapshot(this.scheduled)
            + ", waiting=" + snapshot(this.waiting)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final UUID playerId, final AGCChunkBudget.Operation op, final int radius) {
        final long most = playerId == null ? 0L : playerId.getMostSignificantBits();
        final long least = playerId == null ? 0L : playerId.getLeastSignificantBits();
        return Long.rotateLeft(most, 13) ^ Long.rotateRight(least, 7) ^ ((long) op.ordinal() << 32) ^ radius;
    }

    private static Map<AGCChunkBudget.Operation, Long> snapshot(final EnumMap<AGCChunkBudget.Operation, AtomicLong> source) {
        final EnumMap<AGCChunkBudget.Operation, Long> copy = new EnumMap<>(AGCChunkBudget.Operation.class);
        for (final Map.Entry<AGCChunkBudget.Operation, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    public record Frontier(boolean admitted, AGCChunkBudget.Operation operation, int rings, int width, long cost, long remainingTokens, long stableKey, String reason) {}
}
