package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Predictive FIFO-compatible chunk horizon compiler for alpha24. */
public final class AGCChunkHorizonCompiler {
    public static final AGCChunkHorizonCompiler INSTANCE = new AGCChunkHorizonCompiler();

    private final EnumMap<AGCChunkBudget.Operation, AtomicLong> compiled = new EnumMap<>(AGCChunkBudget.Operation.class);
    private final EnumMap<AGCChunkBudget.Operation, AtomicLong> waiting = new EnumMap<>(AGCChunkBudget.Operation.class);
    private volatile long tickSequence;
    private volatile long horizonTokens = 524_288L;
    private volatile long remainingTokens = 524_288L;
    private volatile String lastReason = "cold";

    private AGCChunkHorizonCompiler() {
        for (final AGCChunkBudget.Operation op : AGCChunkBudget.Operation.values()) {
            this.compiled.put(op, new AtomicLong());
            this.waiting.put(op, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.horizonTokens = Math.max(131_072L, processors * 128_000L);
        this.remainingTokens = this.horizonTokens;
        this.lastReason = "reset processors=" + processors;
    }

    public Horizon compile(final UUID playerId, final AGCChunkBudget.Operation operation, final int radius, final int clusterSize, final String reason) {
        final AGCChunkBudget.Operation op = operation == null ? AGCChunkBudget.Operation.LOAD : operation;
        final int safeRadius = Math.max(1, radius);
        final int safeCluster = Math.max(1, clusterSize);
        final int stripes = Math.max(1, Math.min(256, safeRadius * 2 + safeCluster / 32));
        final int horizonWidth = Math.max(1, Math.min(1024, stripes * 4));
        final long cost = Math.max(1L, (long) horizonWidth * Math.max(1, safeCluster / 16));
        if (cost > this.remainingTokens) {
            this.waiting.get(op).incrementAndGet();
            this.lastReason = "chunk horizon waits FIFO-compatible; no chunk queue reorder: " + safe(reason);
            return new Horizon(false, op, stripes, horizonWidth, cost, this.remainingTokens, stableKey(playerId, op, safeRadius, safeCluster), this.lastReason);
        }
        this.remainingTokens -= cost;
        this.compiled.get(op).incrementAndGet();
        this.lastReason = "compiled chunk horizon op=" + op + " stripes=" + stripes + " width=" + horizonWidth;
        return new Horizon(true, op, stripes, horizonWidth, cost, this.remainingTokens, stableKey(playerId, op, safeRadius, safeCluster), this.lastReason);
    }

    public String statusLine() {
        return "AGCChunkHorizonCompiler{tick=" + this.tickSequence
            + ", horizonTokens=" + this.horizonTokens
            + ", remainingTokens=" + this.remainingTokens
            + ", compiled=" + snapshot(this.compiled)
            + ", waiting=" + snapshot(this.waiting)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final UUID playerId, final AGCChunkBudget.Operation op, final int radius, final int cluster) {
        final long most = playerId == null ? 0L : playerId.getMostSignificantBits();
        final long least = playerId == null ? 0L : playerId.getLeastSignificantBits();
        return Long.rotateLeft(most, 19) ^ Long.rotateRight(least, 11) ^ ((long) op.ordinal() << 40) ^ ((long) radius << 16) ^ cluster;
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

    public record Horizon(boolean admitted, AGCChunkBudget.Operation operation, int stripes, int horizonWidth, long cost, long remainingTokens, long stableKey, String reason) {}
}
