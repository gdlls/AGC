package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha23 compiled dispatch table for lossless scale work.
 *
 * This is a tick-local planner only. It compiles invisible helper jobs into
 * stable slots so the runtime can reduce dispatch overhead without moving
 * Bukkit-visible mutation, plugin events, redstone, entity ticks or chunk
 * completion into worker threads.
 */
public final class AGCScale23DispatchTable {
    public static final AGCScale23DispatchTable INSTANCE = new AGCScale23DispatchTable();

    public enum Lane {
        NETWORK_DIFF_VECTOR,
        CHUNK_FRONTIER,
        ENTITY_VISIBILITY_LATTICE,
        WORLD_BARRIER_DAG,
        PLUGIN_COMMIT_COALESCE,
        WORKER_ROUTE
    }

    private final EnumMap<Lane, AtomicLong> requested = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> compiled = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> orderedVisible = new EnumMap<>(Lane.class);
    private volatile long tickSequence;
    private volatile long baseSlots = 1_000_000L;
    private volatile long remainingSlots = 1_000_000L;
    private volatile int slotCursor;
    private volatile String lastReason = "cold";

    private AGCScale23DispatchTable() {
        for (final Lane lane : Lane.values()) {
            this.requested.put(lane, new AtomicLong());
            this.compiled.put(lane, new AtomicLong());
            this.orderedVisible.put(lane, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final long maxMemory = Math.max(1L, Runtime.getRuntime().maxMemory());
        final long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final double memoryHeadroom = clamp(1.0D - ((double) usedMemory / (double) maxMemory), 0.18D, 1.0D);
        this.baseSlots = Math.max(262_144L, (long) (processors * 384_000L * memoryHeadroom));
        this.remainingSlots = this.baseSlots;
        this.slotCursor = 0;
        this.lastReason = "reset processors=" + processors + " memoryHeadroom=" + round(memoryHeadroom);
    }

    public Dispatch compile(final Lane lane, final long estimatedCost, final boolean visible, final String reason) {
        final Lane safeLane = lane == null ? Lane.WORKER_ROUTE : lane;
        final long safeCost = Math.max(1L, estimatedCost);
        this.requested.get(safeLane).incrementAndGet();
        if (visible) {
            this.orderedVisible.get(safeLane).incrementAndGet();
            this.lastReason = "visible operation stays in ordered commit: " + safe(reason);
            return new Dispatch(false, true, this.slotCursor, this.remainingSlots, this.lastReason);
        }
        if (safeCost > this.remainingSlots) {
            this.lastReason = "dispatch table saturated; invisible helper waits without semantic change: " + safe(reason);
            return new Dispatch(false, false, this.slotCursor, this.remainingSlots, this.lastReason);
        }
        this.remainingSlots -= safeCost;
        final int slot = this.slotCursor++ & 0x7fff_ffff;
        this.compiled.get(safeLane).incrementAndGet();
        this.lastReason = "compiled lossless helper slot=" + slot + " lane=" + safeLane + " reason=" + safe(reason);
        return new Dispatch(true, false, slot, this.remainingSlots, this.lastReason);
    }

    public String statusLine() {
        return "AGCScale23DispatchTable{tick=" + this.tickSequence
            + ", baseSlots=" + this.baseSlots
            + ", remainingSlots=" + this.remainingSlots
            + ", requested=" + snapshot(this.requested)
            + ", compiled=" + snapshot(this.compiled)
            + ", orderedVisible=" + snapshot(this.orderedVisible)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(final double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    private static Map<Lane, Long> snapshot(final EnumMap<Lane, AtomicLong> source) {
        final EnumMap<Lane, Long> copy = new EnumMap<>(Lane.class);
        for (final Map.Entry<Lane, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    public record Dispatch(boolean compiled, boolean orderedVisible, int slot, long remainingSlots, String reason) {}
}
