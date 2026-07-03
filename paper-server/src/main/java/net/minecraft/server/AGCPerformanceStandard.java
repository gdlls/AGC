package net.minecraft.server;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hard AGC performance contract for aggressive mode.
 * <p>
 * This class does not switch gameplay semantics. It defines measurable SLOs
 * that every optimisation lane must respect: no packet drops, no player FIFO
 * reordering, no chunk queue head skipping, no Bukkit-visible off-thread
 * mutation, and bounded deadline batching. Other controllers use it as the
 * shared standard instead of inventing local shortcuts.
 */
public final class AGCPerformanceStandard {
    public static final AGCPerformanceStandard INSTANCE = new AGCPerformanceStandard();

    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong softBreaches = new AtomicLong();
    private final AtomicLong hardBreaches = new AtomicLong();
    private final AtomicLong invariantBreaches = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile double targetMspt = 45.0D;
    private volatile double hardMspt = 100.0D;
    private volatile int maxNetworkDeadlineTicks = 1;
    private volatile int maxOrderedCommitDrainTicks = 1;
    private volatile long maxSemanticQueueDepth = 65_536L;
    private volatile long lastTickNanos;
    private volatile double lastMspt;
    private volatile Pressure lastPressure = Pressure.NORMAL;

    private AGCPerformanceStandard() {
    }

    public void configure(
        final boolean enabled,
        final double targetMspt,
        final double hardMspt,
        final int maxNetworkDeadlineTicks,
        final int maxOrderedCommitDrainTicks,
        final long maxSemanticQueueDepth
    ) {
        this.enabled = enabled;
        this.targetMspt = clamp(targetMspt, 1.0D, 50.0D);
        this.hardMspt = Math.max(this.targetMspt, hardMspt);
        this.maxNetworkDeadlineTicks = Math.max(0, maxNetworkDeadlineTicks);
        this.maxOrderedCommitDrainTicks = Math.max(1, maxOrderedCommitDrainTicks);
        this.maxSemanticQueueDepth = Math.max(1L, maxSemanticQueueDepth);
    }

    public Pressure sample(final long tickNanos, final long semanticQueueDepth, final long deferredFlushDepth, final long orderedCommitDepth) {
        this.samples.incrementAndGet();
        this.lastTickNanos = Math.max(0L, tickNanos);
        this.lastMspt = this.lastTickNanos / (double) TimeUnit.MILLISECONDS.toNanos(1L);
        if (!this.enabled) {
            this.lastPressure = Pressure.NORMAL;
            return this.lastPressure;
        }
        if (semanticQueueDepth > this.maxSemanticQueueDepth || deferredFlushDepth > this.maxSemanticQueueDepth || orderedCommitDepth > this.maxSemanticQueueDepth) {
            this.invariantBreaches.incrementAndGet();
            this.lastPressure = Pressure.INVARIANT_GUARD;
            return this.lastPressure;
        }
        if (this.lastMspt >= this.hardMspt) {
            this.hardBreaches.incrementAndGet();
            this.lastPressure = Pressure.HARD;
            return this.lastPressure;
        }
        if (this.lastMspt >= this.targetMspt) {
            this.softBreaches.incrementAndGet();
            this.lastPressure = Pressure.SOFT;
            return this.lastPressure;
        }
        this.lastPressure = Pressure.NORMAL;
        return this.lastPressure;
    }

    public boolean allowsDeadlineTicks(final int ticks) {
        return !this.enabled || ticks <= this.maxNetworkDeadlineTicks;
    }

    public boolean allowsOrderedCommitDrainTicks(final int ticks) {
        return !this.enabled || ticks <= this.maxOrderedCommitDrainTicks;
    }

    public Pressure pressure() {
        return this.lastPressure;
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.targetMspt,
            this.hardMspt,
            this.maxNetworkDeadlineTicks,
            this.maxOrderedCommitDrainTicks,
            this.maxSemanticQueueDepth,
            this.samples.get(),
            this.softBreaches.get(),
            this.hardBreaches.get(),
            this.invariantBreaches.get(),
            this.lastTickNanos,
            this.lastMspt,
            this.lastPressure
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCPerformanceStandard{enabled=" + snapshot.enabled()
            + ", targetMspt=" + snapshot.targetMspt()
            + ", hardMspt=" + snapshot.hardMspt()
            + ", maxNetworkDeadlineTicks=" + snapshot.maxNetworkDeadlineTicks()
            + ", maxOrderedCommitDrainTicks=" + snapshot.maxOrderedCommitDrainTicks()
            + ", maxSemanticQueueDepth=" + snapshot.maxSemanticQueueDepth()
            + ", pressure=" + snapshot.pressure()
            + ", lastMspt=" + snapshot.lastMspt()
            + ", samples=" + snapshot.samples()
            + ", softBreaches=" + snapshot.softBreaches()
            + ", hardBreaches=" + snapshot.hardBreaches()
            + ", invariantBreaches=" + snapshot.invariantBreaches()
            + '}';
    }

    private static double clamp(final double value, final double min, final double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public enum Pressure {
        NORMAL,
        SOFT,
        HARD,
        INVARIANT_GUARD
    }

    public record Snapshot(
        boolean enabled,
        double targetMspt,
        double hardMspt,
        int maxNetworkDeadlineTicks,
        int maxOrderedCommitDrainTicks,
        long maxSemanticQueueDepth,
        long samples,
        long softBreaches,
        long hardBreaches,
        long invariantBreaches,
        long lastTickNanos,
        double lastMspt,
        Pressure pressure
    ) {
    }
}
