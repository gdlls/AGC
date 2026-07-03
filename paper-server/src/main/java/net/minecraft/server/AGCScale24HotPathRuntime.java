package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha24 hot-path runtime.
 *
 * The runtime gives budget only to lossless helper work that shortens the next
 * visible tick path. It never admits Bukkit-visible mutation, redstone, block
 * entity ticks, entity ticks, plugin events, chunk completion or connection
 * writes onto worker lanes.
 */
public final class AGCScale24HotPathRuntime {
    public static final AGCScale24HotPathRuntime INSTANCE = new AGCScale24HotPathRuntime();

    public enum Domain {
        NETWORK_ORDER_VECTOR,
        CHUNK_HORIZON,
        ENTITY_OBSERVER_SET,
        WORLD_READ_ONLY_BATCH,
        PLUGIN_TICKET_CACHE,
        RESOURCE_LOCALITY
    }

    private final EnumMap<Domain, AtomicLong> admitted = new EnumMap<>(Domain.class);
    private final EnumMap<Domain, AtomicLong> waiting = new EnumMap<>(Domain.class);
    private volatile long tickSequence;
    private volatile long baseCredits = 1_000_000L;
    private volatile long remainingCredits = 1_000_000L;
    private volatile String lastReason = "cold";

    private AGCScale24HotPathRuntime() {
        for (final Domain domain : Domain.values()) {
            this.admitted.put(domain, new AtomicLong());
            this.waiting.put(domain, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final long maxMemory = Math.max(1L, Runtime.getRuntime().maxMemory());
        final long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final double headroom = Math.max(0.15D, Math.min(1.0D, 1.0D - ((double) usedMemory / (double) maxMemory)));
        final long reservedForPrimaryAndPlugins = Math.max(196_608L, processors * 96_000L);
        this.baseCredits = Math.max(262_144L, (long) (processors * 448_000L * headroom) - reservedForPrimaryAndPlugins);
        this.remainingCredits = this.baseCredits;
        this.lastReason = "reset processors=" + processors + " memoryHeadroom=" + round(headroom);
    }

    public Grant claim(final Domain domain, final long estimatedCost, final boolean visible, final String reason) {
        final Domain safeDomain = domain == null ? Domain.RESOURCE_LOCALITY : domain;
        final long cost = Math.max(1L, estimatedCost);
        if (visible) {
            this.waiting.get(safeDomain).incrementAndGet();
            this.lastReason = "visible operation is not hot-path helper work: " + safe(reason);
            return new Grant(false, true, cost, this.remainingCredits, this.lastReason);
        }
        if (cost > this.remainingCredits) {
            this.waiting.get(safeDomain).incrementAndGet();
            this.lastReason = "hot-path helper waits; visible order is unchanged: " + safe(reason);
            return new Grant(false, false, cost, this.remainingCredits, this.lastReason);
        }
        this.remainingCredits -= cost;
        this.admitted.get(safeDomain).incrementAndGet();
        this.lastReason = "admitted alpha24 hot-path helper domain=" + safeDomain + " cost=" + cost + " reason=" + safe(reason);
        return new Grant(true, false, cost, this.remainingCredits, this.lastReason);
    }

    public String statusLine() {
        return "AGCScale24HotPathRuntime{tick=" + this.tickSequence
            + ", baseCredits=" + this.baseCredits
            + ", remainingCredits=" + this.remainingCredits
            + ", admitted=" + snapshot(this.admitted)
            + ", waiting=" + snapshot(this.waiting)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static Map<Domain, Long> snapshot(final EnumMap<Domain, AtomicLong> source) {
        final EnumMap<Domain, Long> copy = new EnumMap<>(Domain.class);
        for (final Map.Entry<Domain, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    private static double round(final double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    public record Grant(boolean admitted, boolean orderedVisible, long cost, long remainingCredits, String reason) {}
}
