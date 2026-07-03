package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** Cache/locality aware scheduler for alpha24 read-only helper slots. */
public final class AGCResourceLocalityScheduler {
    public static final AGCResourceLocalityScheduler INSTANCE = new AGCResourceLocalityScheduler();

    private final AtomicLong placed = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();
    private volatile long tickSequence;
    private volatile long localityBudgetBytes = 256L * 1024L * 1024L;
    private volatile long remainingBytes = 256L * 1024L * 1024L;
    private volatile String lastReason = "cold";

    private AGCResourceLocalityScheduler() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final long maxMemory = Math.max(64L * 1024L * 1024L, Runtime.getRuntime().maxMemory());
        this.localityBudgetBytes = Math.max(64L * 1024L * 1024L, Math.min(maxMemory / 5L, processors * 64L * 1024L * 1024L));
        this.remainingBytes = this.localityBudgetBytes;
        this.lastReason = "reset processors=" + processors + " budgetBytes=" + this.localityBudgetBytes;
    }

    public Slot place(final String key, final long bytes, final boolean visible) {
        final long safeBytes = Math.max(1L, bytes);
        if (visible) {
            this.delayed.incrementAndGet();
            this.lastReason = "visible work is not routed through locality helper slot: " + safe(key);
            return new Slot(false, true, 0, safeBytes, this.remainingBytes, this.lastReason);
        }
        if (safeBytes > this.remainingBytes) {
            this.delayed.incrementAndGet();
            this.lastReason = "locality helper waits; no visible semantic change key=" + safe(key);
            return new Slot(false, false, 0, safeBytes, this.remainingBytes, this.lastReason);
        }
        this.remainingBytes -= safeBytes;
        final int slot = (int) (this.placed.incrementAndGet() & 0x7fff_ffffL);
        this.lastReason = "placed read-only locality helper slot=" + slot + " key=" + safe(key) + " bytes=" + safeBytes;
        return new Slot(true, false, slot, safeBytes, this.remainingBytes, this.lastReason);
    }

    public String statusLine() {
        return "AGCResourceLocalityScheduler{tick=" + this.tickSequence
            + ", budgetBytes=" + this.localityBudgetBytes
            + ", remainingBytes=" + this.remainingBytes
            + ", placed=" + this.placed.get()
            + ", delayed=" + this.delayed.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String key) {
        return key == null || key.isBlank() ? "unspecified" : key;
    }

    public record Slot(boolean placed, boolean orderedVisible, int slot, long bytes, long remainingBytes, String reason) {}
}
