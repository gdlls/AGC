package net.minecraft.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local memory locality planner. It budgets scratch/cache planning so AGC
 * reduces repeated allocation churn in hotspots without retaining live world
 * state across ticks.
 */
public final class AGCMemoryLocalityPlanner {
    public static final AGCMemoryLocalityPlanner INSTANCE = new AGCMemoryLocalityPlanner();

    private final ConcurrentHashMap<String, AtomicLong> scratchOwners = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile long maxScratchBytesPerTick = 128L * 1024L * 1024L;
    private volatile long remainingScratchBytes = maxScratchBytesPerTick;
    private volatile int maxOwners = 65_536;
    private volatile long tickSequence;

    private AGCMemoryLocalityPlanner() {
    }

    public void configure(final boolean enabled, final long maxScratchBytesPerTick, final int maxOwners) {
        this.enabled = enabled;
        this.maxScratchBytesPerTick = Math.max(1024L, maxScratchBytesPerTick);
        this.maxOwners = Math.max(1, maxOwners);
    }

    public void beginTick(final long sequence) {
        if (sequence != this.tickSequence) {
            this.tickSequence = sequence;
            this.scratchOwners.clear();
            this.remainingScratchBytes = this.maxScratchBytesPerTick;
        }
    }

    public Admission claimScratch(final String owner, final long bytes, final String reason) {
        this.requests.incrementAndGet();
        if (!this.enabled) {
            this.delayed.incrementAndGet();
            return new Admission(false, this.remainingScratchBytes, "memory locality disabled: " + safe(reason));
        }
        final long cost = Math.max(1L, bytes);
        if (this.scratchOwners.size() >= this.maxOwners && !this.scratchOwners.containsKey(safe(owner))) {
            this.delayed.incrementAndGet();
            return new Admission(false, this.remainingScratchBytes, "scratch owner table full; avoid long-lived allocation pressure");
        }
        final AGCScaleControlPlane.Admission control = AGCScaleControlPlane.INSTANCE.claim(AGCScaleControlPlane.Lane.MEMORY_LOCALITY, Math.max(1L, cost / 4096L), owner);
        if (!control.admitted()) {
            this.delayed.incrementAndGet();
            return new Admission(false, this.remainingScratchBytes, control.reason());
        }
        synchronized (this) {
            if (this.remainingScratchBytes < cost) {
                this.delayed.incrementAndGet();
                return new Admission(false, this.remainingScratchBytes, "scratch budget waits; no live-state cache retained");
            }
            this.remainingScratchBytes -= cost;
        }
        this.scratchOwners.computeIfAbsent(safe(owner), ignored -> new AtomicLong()).addAndGet(cost);
        this.admitted.incrementAndGet();
        return new Admission(true, this.remainingScratchBytes, safe(reason));
    }

    public String statusLine() {
        return "AGCMemoryLocalityPlanner{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", owners=" + this.scratchOwners.size()
            + ", remainingScratchBytes=" + this.remainingScratchBytes
            + ", requests=" + this.requests.get()
            + ", admitted=" + this.admitted.get()
            + ", delayed=" + this.delayed.get()
            + '}';
    }

    private static String safe(final String value) {
        return value == null ? "unknown" : value;
    }

    public record Admission(boolean admitted, long remainingScratchBytes, String reason) {
    }
}
