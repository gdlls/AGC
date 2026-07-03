package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Counts estimated duplicate work avoided by semantic-safe algorithmic planning. */
public final class AGCComputeWasteReducer {
    public static final AGCComputeWasteReducer INSTANCE = new AGCComputeWasteReducer();

    public enum Kind {
        NETWORK_SHAPE,
        CHUNK_INTENT,
        ENTITY_CANDIDATE,
        WORLD_PLAN,
        PLUGIN_CLASSIFY,
        MEMORY_SCRATCH
    }

    private final EnumMap<Kind, AtomicLong> events = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, AtomicLong> estimatedSavedUnits = new EnumMap<>(Kind.class);
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCComputeWasteReducer() {
        for (final Kind kind : Kind.values()) {
            this.events.put(kind, new AtomicLong());
            this.estimatedSavedUnits.put(kind, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "reset";
    }

    public void record(final Kind kind, final long beforeUnits, final long afterUnits, final String reason) {
        final Kind safeKind = kind == null ? Kind.MEMORY_SCRATCH : kind;
        final long saved = Math.max(0L, beforeUnits - afterUnits);
        this.events.get(safeKind).incrementAndGet();
        this.estimatedSavedUnits.get(safeKind).addAndGet(saved);
        this.lastReason = "kind=" + safeKind + " saved=" + saved + " reason=" + (reason == null ? "unspecified" : reason);
    }

    public String statusLine() {
        return "AGCComputeWasteReducer{tick=" + this.tickSequence
            + ", events=" + snapshot(this.events)
            + ", estimatedSavedUnits=" + snapshot(this.estimatedSavedUnits)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static Map<Kind, Long> snapshot(final EnumMap<Kind, AtomicLong> source) {
        final EnumMap<Kind, Long> copy = new EnumMap<>(Kind.class);
        for (final Map.Entry<Kind, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }
}
