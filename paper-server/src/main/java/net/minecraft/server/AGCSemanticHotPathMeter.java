package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Measures where AGC shortened invisible hot paths without changing semantics. */
public final class AGCSemanticHotPathMeter {
    public static final AGCSemanticHotPathMeter INSTANCE = new AGCSemanticHotPathMeter();

    public enum Kind {
        NETWORK_BACKBONE,
        CHUNK_FORECAST,
        ENTITY_OBSERVER,
        WORLD_PHASE,
        PLUGIN_LEDGER,
        LOAD_SHAPING
    }

    private final EnumMap<Kind, AtomicLong> raw = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, AtomicLong> optimized = new EnumMap<>(Kind.class);
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCSemanticHotPathMeter() {
        for (final Kind kind : Kind.values()) {
            this.raw.put(kind, new AtomicLong());
            this.optimized.put(kind, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "reset";
    }

    public void record(final Kind kind, final long rawCost, final long optimizedCost, final String reason) {
        final Kind safeKind = kind == null ? Kind.LOAD_SHAPING : kind;
        this.raw.get(safeKind).addAndGet(Math.max(0L, rawCost));
        this.optimized.get(safeKind).addAndGet(Math.max(0L, optimizedCost));
        this.lastReason = safeKind + " raw=" + rawCost + " optimized=" + optimizedCost + " reason=" + (reason == null ? "" : reason);
    }

    public String statusLine() {
        return "AGCSemanticHotPathMeter{tick=" + this.tickSequence
            + ", raw=" + snapshot(this.raw)
            + ", optimized=" + snapshot(this.optimized)
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
