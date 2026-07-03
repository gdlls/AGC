package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha22 central compiler for tick-local invisible work.
 *
 * The compiler does not own live Minecraft state. It ranks only helper work that
 * can reduce the next visible critical path while keeping Bukkit-visible phases
 * ordered: packet send graph preparation, FIFO-compatible chunk demand planning,
 * entity observer preparation, multiverse read-only slots and plugin commit
 * ledger bookkeeping.
 */
public final class AGCScale22TickWorkCompiler {
    public static final AGCScale22TickWorkCompiler INSTANCE = new AGCScale22TickWorkCompiler();

    public enum Track {
        NETWORK_SEND_GRAPH,
        CHUNK_DEMAND,
        ENTITY_OBSERVER,
        WORLD_PHASE,
        PLUGIN_COMMIT_LEDGER,
        SYSTEM_LOAD
    }

    private final EnumMap<Track, AtomicLong> requested = new EnumMap<>(Track.class);
    private final EnumMap<Track, AtomicLong> compiled = new EnumMap<>(Track.class);
    private final EnumMap<Track, AtomicLong> delayed = new EnumMap<>(Track.class);
    private volatile long tickSequence;
    private volatile long baseWorkUnits = 1_000_000L;
    private volatile long remainingWorkUnits = 1_000_000L;
    private volatile String lastReason = "cold";

    private AGCScale22TickWorkCompiler() {
        for (final Track track : Track.values()) {
            this.requested.put(track, new AtomicLong());
            this.compiled.put(track, new AtomicLong());
            this.delayed.put(track, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final long maxMemory = Math.max(1L, Runtime.getRuntime().maxMemory());
        final double memoryHeadroom = clamp(1.0D - ((double) usedMemory / (double) maxMemory), 0.20D, 1.00D);
        this.baseWorkUnits = Math.max(524_288L, (long) (processors * 512_000L * memoryHeadroom));
        this.remainingWorkUnits = this.baseWorkUnits;
        this.lastReason = "reset processors=" + processors + " memoryHeadroom=" + round(memoryHeadroom);
    }

    public Compile compile(final Track track, final long cost, final boolean visible, final String reason) {
        final Track safeTrack = track == null ? Track.SYSTEM_LOAD : track;
        final long safeCost = Math.max(1L, cost);
        this.requested.get(safeTrack).incrementAndGet();
        if (visible) {
            this.delayed.get(safeTrack).incrementAndGet();
            this.lastReason = "visible work remains ordered: " + safe(reason);
            return new Compile(false, true, this.remainingWorkUnits, this.lastReason);
        }
        if (safeCost > this.remainingWorkUnits) {
            this.delayed.get(safeTrack).incrementAndGet();
            this.lastReason = "defer invisible helper work without changing gameplay order: " + safe(reason);
            return new Compile(false, false, this.remainingWorkUnits, this.lastReason);
        }
        this.remainingWorkUnits -= safeCost;
        this.compiled.get(safeTrack).incrementAndGet();
        this.lastReason = "compile invisible tick work: " + safe(reason);
        return new Compile(true, false, this.remainingWorkUnits, this.lastReason);
    }

    public String statusLine() {
        return "AGCScale22TickWorkCompiler{tick=" + this.tickSequence
            + ", baseWorkUnits=" + this.baseWorkUnits
            + ", remainingWorkUnits=" + this.remainingWorkUnits
            + ", requested=" + snapshot(this.requested)
            + ", compiled=" + snapshot(this.compiled)
            + ", delayed=" + snapshot(this.delayed)
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

    private static Map<Track, Long> snapshot(final EnumMap<Track, AtomicLong> source) {
        final EnumMap<Track, Long> copy = new EnumMap<>(Track.class);
        for (final Map.Entry<Track, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    public record Compile(boolean compiled, boolean orderedVisible, long remainingWorkUnits, String reason) {}
}
