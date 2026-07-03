package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** Tick-local locality window for helper data. It never owns live world state. */
public final class AGCCacheLocalityWindow {
    public static final AGCCacheLocalityWindow INSTANCE = new AGCCacheLocalityWindow();

    private final AtomicLong claims = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private volatile long tickSequence;
    private volatile long maxBytesPerTick = 128L * 1024L * 1024L;
    private volatile long usedBytes;

    private AGCCacheLocalityWindow() {
    }

    public void configure(final long maxBytesPerTick) {
        this.maxBytesPerTick = Math.max(1024L * 1024L, maxBytesPerTick);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.usedBytes = 0L;
    }

    public Admission claim(final String key, final long bytes) {
        final long requested = Math.max(1L, bytes);
        final AGCScale17AlgorithmKernel.Admission budget = AGCScale17AlgorithmKernel.INSTANCE.claim(
            AGCScale17AlgorithmKernel.Plane.CACHE_LOCALITY_WINDOW,
            Math.max(1L, requested / 4096L),
            key
        );
        if (!budget.admitted() || this.usedBytes + requested > this.maxBytesPerTick) {
            this.waits.incrementAndGet();
            return new Admission(false, this.usedBytes, "cache-local helper waits; live state is not retained");
        }
        this.usedBytes += requested;
        this.claims.incrementAndGet();
        return new Admission(true, this.usedBytes, "tick-local helper locality");
    }

    public String statusLine() {
        return "AGCCacheLocalityWindow{tick=" + this.tickSequence
            + ", maxBytesPerTick=" + this.maxBytesPerTick
            + ", usedBytes=" + this.usedBytes
            + ", claims=" + this.claims.get()
            + ", waits=" + this.waits.get()
            + '}';
    }

    public record Admission(boolean admitted, long usedBytes, String reason) {
    }
}
