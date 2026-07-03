package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha20 lossless scale pipeline.
 *
 * This is the big-ticket coordinator for thousand-player survival scale. It
 * only budgets work that can be proven invisible to Minecraft semantics:
 * packet-shape planning, chunk demand routing, entity candidate reduction,
 * read-only world phase planning, plugin semantic classification and cache
 * locality. Ordered commits, entity ticks, block ticks, redstone, Bukkit events
 * and per-connection packet order are not moved into this pipeline.
 */
public final class AGCScale20LosslessPipeline {
    public static final AGCScale20LosslessPipeline INSTANCE = new AGCScale20LosslessPipeline();

    public enum Stage {
        NETWORK_BACKBONE,
        CHUNK_PIPELINE,
        ENTITY_INDEX,
        WORLD_PLAN,
        PLUGIN_TRANSLATION,
        RESOURCE_PLACEMENT
    }

    private final EnumMap<Stage, AtomicLong> requested = new EnumMap<>(Stage.class);
    private final EnumMap<Stage, AtomicLong> admitted = new EnumMap<>(Stage.class);
    private final EnumMap<Stage, AtomicLong> delayed = new EnumMap<>(Stage.class);
    private volatile long tickSequence;
    private volatile long baseCredits;
    private volatile long remainingCredits;
    private volatile double msptEwma = 50.0D;
    private volatile String lastReason = "cold";

    private AGCScale20LosslessPipeline() {
        for (final Stage stage : Stage.values()) {
            this.requested.put(stage, new AtomicLong());
            this.admitted.put(stage, new AtomicLong());
            this.delayed.put(stage, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final long maxMemory = Math.max(1L, Runtime.getRuntime().maxMemory());
        final long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final double memoryHeadroom = Math.max(0.20D, Math.min(1.0D, 1.0D - ((double) usedMemory / (double) maxMemory)));
        final double tickHeadroom = Math.max(0.10D, Math.min(1.0D, 50.0D / Math.max(35.0D, this.msptEwma)));
        this.baseCredits = Math.max(131_072L, (long) (processors * 262_144L * tickHeadroom * memoryHeadroom));
        this.remainingCredits = this.baseCredits;
        this.lastReason = "reset processors=" + processors + " tickHeadroom=" + round(tickHeadroom) + " memoryHeadroom=" + round(memoryHeadroom);
    }

    public void recordMspt(final double mspt) {
        final double sample = Math.max(1.0D, mspt);
        this.msptEwma = this.msptEwma * 0.90D + sample * 0.10D;
    }

    public Grant claim(final Stage stage, final long cost, final String reason) {
        final Stage safeStage = stage == null ? Stage.RESOURCE_PLACEMENT : stage;
        final long safeCost = Math.max(1L, cost);
        this.requested.get(safeStage).incrementAndGet();
        if (safeCost > this.remainingCredits) {
            this.delayed.get(safeStage).incrementAndGet();
            this.lastReason = "delay invisible stage: " + safe(reason);
            return new Grant(false, this.remainingCredits, this.lastReason);
        }
        this.remainingCredits -= safeCost;
        this.admitted.get(safeStage).incrementAndGet();
        this.lastReason = "admit invisible stage: " + safe(reason);
        return new Grant(true, this.remainingCredits, this.lastReason);
    }

    public String statusLine() {
        return "AGCScale20LosslessPipeline{tick=" + this.tickSequence
            + ", msptEwma=" + round(this.msptEwma)
            + ", baseCredits=" + this.baseCredits
            + ", remainingCredits=" + this.remainingCredits
            + ", requested=" + snapshot(this.requested)
            + ", admitted=" + snapshot(this.admitted)
            + ", delayed=" + snapshot(this.delayed)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    private static double round(final double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static Map<Stage, Long> snapshot(final EnumMap<Stage, AtomicLong> source) {
        final EnumMap<Stage, Long> copy = new EnumMap<>(Stage.class);
        for (final Map.Entry<Stage, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    public record Grant(boolean admitted, long remainingCredits, String reason) {}
}
