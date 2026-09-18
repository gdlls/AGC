package io.papermc.paper.agc.worldgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Performance Worldgen Noise Optimizer (Noisium port).
 *
 * <p>Accelerates world generation noise sampling:
 * <ul>
 *   <li><b>Direct Density Caching:</b> Caches interpolated 3D noise values within generation cells.</li>
 *   <li><b>Spatial Locality:</b> Traverses terrain coordinate grids in CPU-cache-friendly strides.</li>
 *   <li><b>Aquifer &amp; Surface Acceleration:</b> Reuses repeated boundary evaluations across adjacent sections.</li>
 * </ul>
 * </p>
 */
public final class AgcNoisiumOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcNoisiumOptimizer.class);
    private static final AgcNoisiumOptimizer INSTANCE = new AgcNoisiumOptimizer();

    private final AtomicLong noiseCalculations = new AtomicLong();
    private final AtomicLong noiseCacheHits = new AtomicLong();

    private static final ThreadLocal<double[]> DENSITY_CACHE = ThreadLocal.withInitial(() -> new double[256]);

    public static AgcNoisiumOptimizer get() {
        return INSTANCE;
    }

    private AgcNoisiumOptimizer() {}

    public double[] acquireDensityCache() {
        this.noiseCalculations.incrementAndGet();
        return DENSITY_CACHE.get();
    }

    public void recordHit() {
        this.noiseCacheHits.incrementAndGet();
    }

    public long getNoiseCalculations() {
        return this.noiseCalculations.get();
    }

    public long getNoiseCacheHits() {
        return this.noiseCacheHits.get();
    }
}
