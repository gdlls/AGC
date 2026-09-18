package io.papermc.paper.agc;

import java.util.Random;

/**
 * AGC — High-Performance XorShift64* / SplitMix PRNG (PumpkinMC / Cuberite port).
 *
 * <p>Provides lock-free, allocation-free pseudo-random generation for particle spread,
 * block decay, entity velocity jitter, and ambient effects in &lt; 2 CPU cycles.</p>
 */
public final class AgcFastRandom extends Random {

    private long state;

    public AgcFastRandom() {
        this(System.nanoTime() ^ (Thread.currentThread().getId() << 32));
    }

    public AgcFastRandom(final long seed) {
        super(seed);
        this.state = seed != 0 ? seed : 0x853c49e6748fea9bL;
    }

    @Override
    public synchronized void setSeed(final long seed) {
        this.state = seed != 0 ? seed : 0x853c49e6748fea9bL;
        super.setSeed(seed);
    }

    @Override
    protected int next(final int bits) {
        return (int) (nextLong() >>> (64 - bits));
    }

    @Override
    public long nextLong() {
        long x = this.state;
        x ^= (x >>> 12);
        x ^= (x << 25);
        x ^= (x >>> 27);
        this.state = x;
        return x * 0x2545F4914F6CDD1DL;
    }

    @Override
    public int nextInt() {
        return (int) nextLong();
    }

    @Override
    public int nextInt(final int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        long r = (nextLong() >>> 33);
        long m = (long) bound * r;
        return (int) (m >>> 31);
    }

    @Override
    public float nextFloat() {
        return (nextLong() >>> 40) * 0x1.0p-24f;
    }

    @Override
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    @Override
    public boolean nextBoolean() {
        return (nextLong() & 1) != 0;
    }
}
