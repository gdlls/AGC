package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha21 critical-path runtime for thousand-player survival scale.
 *
 * This controller only grants budget to invisible work that shortens the next
 * visible tick path: fanout graph planning, FIFO-compatible chunk storm
 * smoothing, entity density field preparation, multiverse lane allocation,
 * plugin semantic sequencing and cache/locality helpers. Bukkit-visible state
 * changes remain ordered and are never moved through this runtime.
 */
public final class AGCScale21CriticalPathRuntime {
    public static final AGCScale21CriticalPathRuntime INSTANCE = new AGCScale21CriticalPathRuntime();

    public enum Domain {
        NETWORK_FANOUT,
        CHUNK_STORM,
        ENTITY_DENSITY,
        WORLD_LANE,
        PLUGIN_SEQUENCE,
        RESOURCE_LOCALITY
    }

    private final EnumMap<Domain, AtomicLong> requested = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> admitted = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> smoothed = new EnumMap<>(Domain.class);
    private volatile long tickSequence;
    private volatile long baseCredits;
    private volatile long remainingCredits;
    private volatile double targetMspt = 50.0D;
    private volatile double msptEwma = 45.0D;
    private volatile String lastReason = "cold";

    private AGCScale21CriticalPathRuntime() {
        for (final Domain domain : Domain.values()) {
            this.requested.put(domain, new AtomicLong());
            this.admitted.put(domain, new AtomicLong());
            this.smoothed.put(domain, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final long maxMemory = Math.max(1L, Runtime.getRuntime().maxMemory());
        final long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final double memoryHeadroom = clamp(1.0D - ((double) usedMemory / (double) maxMemory), 0.15D, 1.0D);
        final double tickHeadroom = clamp(this.targetMspt / Math.max(30.0D, this.msptEwma), 0.20D, 1.25D);
        final long perCore = 384_000L;
        this.baseCredits = Math.max(262_144L, (long) (processors * perCore * memoryHeadroom * tickHeadroom));
        this.remainingCredits = this.baseCredits;
        this.lastReason = "reset processors=" + processors + " tickHeadroom=" + round(tickHeadroom) + " memoryHeadroom=" + round(memoryHeadroom);
    }

    public void recordMspt(final double mspt) {
        final double sample = Math.max(1.0D, mspt);
        this.msptEwma = this.msptEwma * 0.88D + sample * 0.12D;
    }

    public Grant claim(final Domain domain, final long cost, final boolean bukkitVisible, final String reason) {
        final Domain safeDomain = domain == null ? Domain.RESOURCE_LOCALITY : domain;
        final long safeCost = Math.max(1L, cost);
        this.requested.get(safeDomain).incrementAndGet();
        if (bukkitVisible) {
            this.smoothed.get(safeDomain).incrementAndGet();
            this.lastReason = "visible work remains ordered: " + safe(reason);
            return new Grant(false, true, this.remainingCredits, this.lastReason);
        }
        if (safeCost > this.remainingCredits) {
            this.smoothed.get(safeDomain).incrementAndGet();
            this.lastReason = "smooth invisible critical-path work: " + safe(reason);
            return new Grant(false, false, this.remainingCredits, this.lastReason);
        }
        this.remainingCredits -= safeCost;
        this.admitted.get(safeDomain).incrementAndGet();
        this.lastReason = "admit critical-path invisible work: " + safe(reason);
        return new Grant(true, false, this.remainingCredits, this.lastReason);
    }

    public String statusLine() {
        return "AGCScale21CriticalPathRuntime{tick=" + this.tickSequence
            + ", msptEwma=" + round(this.msptEwma)
            + ", baseCredits=" + this.baseCredits
            + ", remainingCredits=" + this.remainingCredits
            + ", requested=" + snapshot(this.requested)
            + ", admitted=" + snapshot(this.admitted)
            + ", smoothed=" + snapshot(this.smoothed)
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

    private static Map<Domain, Long> snapshot(final EnumMap<Domain, AtomicLong> source) {
        final EnumMap<Domain, Long> copy = new EnumMap<>(Domain.class);
        for (final Map.Entry<Domain, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    public record Grant(boolean admitted, boolean orderedVisible, long remainingCredits, String reason) {}
}
