package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha19 high-scale logic kernel.
 *
 * This kernel does not change Minecraft-visible state. It gives budget only to
 * lossless algorithms: demand forecasting, recipient graph construction,
 * entity-interest set reduction, read-only phase batching and plugin semantic
 * classification. Ordered commits, redstone/block/entity ticks and Bukkit event
 * callbacks remain outside this budget and keep vanilla/Paper order.
 */
public final class AGCScale19LogicKernel {
    public static final AGCScale19LogicKernel INSTANCE = new AGCScale19LogicKernel();

    public enum Axis {
        NETWORK_GRAPH,
        CHUNK_FORECAST,
        ENTITY_INTEREST,
        WORLD_PHASE_BATCH,
        PLUGIN_SEMANTIC_JIT,
        LOCALITY_RING
    }

    private final EnumMap<Axis, AtomicLong> requested = new EnumMap<>(Axis.class);
    private final EnumMap<Axis, AtomicLong> admitted = new EnumMap<>(Axis.class);
    private final EnumMap<Axis, AtomicLong> delayed = new EnumMap<>(Axis.class);
    private volatile long tickSequence;
    private volatile long baseBudget;
    private volatile long remainingBudget;
    private volatile double msptEwma = 50.0D;
    private volatile String lastReason = "cold";

    private AGCScale19LogicKernel() {
        for (final Axis axis : Axis.values()) {
            this.requested.put(axis, new AtomicLong());
            this.admitted.put(axis, new AtomicLong());
            this.delayed.put(axis, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final double pressure = Math.max(0.15D, Math.min(1.0D, 50.0D / Math.max(35.0D, this.msptEwma)));
        this.baseBudget = Math.max(65_536L, (long) (processors * 196_608L * pressure));
        this.remainingBudget = this.baseBudget;
        this.lastReason = "alpha19 logic budget reset processors=" + processors + " pressure=" + pressure;
    }

    public void recordMspt(final double mspt) {
        final double sample = Math.max(1.0D, mspt);
        this.msptEwma = this.msptEwma * 0.875D + sample * 0.125D;
    }

    public Grant claim(final Axis axis, final long cost, final String reason) {
        final Axis safeAxis = axis == null ? Axis.LOCALITY_RING : axis;
        final long safeCost = Math.max(1L, cost);
        this.requested.get(safeAxis).incrementAndGet();
        if (safeCost > this.remainingBudget) {
            this.delayed.get(safeAxis).incrementAndGet();
            this.lastReason = "defer read-only logic: " + safe(reason);
            return new Grant(false, this.remainingBudget, this.lastReason);
        }
        this.remainingBudget -= safeCost;
        this.admitted.get(safeAxis).incrementAndGet();
        this.lastReason = "admit read-only logic: " + safe(reason);
        return new Grant(true, this.remainingBudget, this.lastReason);
    }

    public String statusLine() {
        return "AGCScale19LogicKernel{tick=" + this.tickSequence
            + ", msptEwma=" + Math.round(this.msptEwma * 100.0D) / 100.0D
            + ", baseBudget=" + this.baseBudget
            + ", remaining=" + this.remainingBudget
            + ", requested=" + snapshot(this.requested)
            + ", admitted=" + snapshot(this.admitted)
            + ", delayed=" + snapshot(this.delayed)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    private static Map<Axis, Long> snapshot(final EnumMap<Axis, AtomicLong> source) {
        final EnumMap<Axis, Long> copy = new EnumMap<>(Axis.class);
        for (final Map.Entry<Axis, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    public record Grant(boolean admitted, long remainingBudget, String reason) {}
}
