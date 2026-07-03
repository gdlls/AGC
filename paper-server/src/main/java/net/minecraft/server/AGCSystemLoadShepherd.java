package net.minecraft.server;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicLong;

/** Reserves CPU/memory headroom before helper work is admitted. */
public final class AGCSystemLoadShepherd {
    public static final AGCSystemLoadShepherd INSTANCE = new AGCSystemLoadShepherd();

    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong shaped = new AtomicLong();
    private volatile long tickSequence;
    private volatile int reservedCpuPercent = 36;
    private volatile double loadScore = 0.5D;
    private volatile String lastReason = "cold";

    private AGCSystemLoadShepherd() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final long maxMemory = Math.max(1L, Runtime.getRuntime().maxMemory());
        final double memoryPressure = Math.min(1.0D, (double) usedMemory / (double) maxMemory);
        double systemLoad = 0.50D;
        try {
            final OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            final double load = bean.getSystemLoadAverage();
            if (load >= 0.0D) {
                systemLoad = Math.min(1.0D, load / processors);
            }
        } catch (final Throwable ignored) {
            systemLoad = 0.50D;
        }
        this.loadScore = Math.max(0.0D, Math.min(1.0D, systemLoad * 0.65D + memoryPressure * 0.35D));
        this.reservedCpuPercent = this.loadScore > 0.82D ? 48 : this.loadScore > 0.65D ? 42 : 36;
        this.lastReason = "reset loadScore=" + round(this.loadScore) + " reservedCpu=" + this.reservedCpuPercent;
    }

    public Permit permit(final String domain, final long cost, final boolean visible) {
        final long safeCost = Math.max(1L, cost);
        if (visible) {
            this.shaped.incrementAndGet();
            this.lastReason = "visible work bypasses helper shepherd domain=" + safe(domain);
            return new Permit(false, true, this.reservedCpuPercent, this.lastReason);
        }
        final long allowedCost = this.loadScore > 0.82D ? 2048L : this.loadScore > 0.65D ? 8192L : 65_536L;
        final boolean ok = safeCost <= allowedCost;
        if (ok) {
            this.admitted.incrementAndGet();
        } else {
            this.shaped.incrementAndGet();
        }
        this.lastReason = (ok ? "permit" : "shape") + " helper work domain=" + safe(domain) + " cost=" + safeCost + " allowed=" + allowedCost;
        return new Permit(ok, false, this.reservedCpuPercent, this.lastReason);
    }

    public String statusLine() {
        return "AGCSystemLoadShepherd{tick=" + this.tickSequence
            + ", loadScore=" + round(this.loadScore)
            + ", reservedCpuPercent=" + this.reservedCpuPercent
            + ", admitted=" + this.admitted.get()
            + ", shaped=" + this.shaped.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String value) { return value == null || value.isBlank() ? "unknown" : value; }
    private static double round(final double value) { return Math.round(value * 100.0D) / 100.0D; }

    public record Permit(boolean admitted, boolean orderedVisible, int reservedCpuPercent, String reason) {}
}
