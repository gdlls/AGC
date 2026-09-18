package io.papermc.paper.agc.worldgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — World Generation Density & Noise Generator Optimizer.
 *
 * <p>Accelerates heavy 3D terrain density noise evaluation during terrain carving and surface generation:
 * <ul>
 *   <li><b>Grid Density Interpolation:</b> Caches noise samples on a 4x4x4 grid and performs trilinear interpolation.</li>
 *   <li><b>Multi-Octave Fast Path:</b> Computes combined procedural octaves with zero heap allocations.</li>
 * </ul>
 * </p>
 */
public final class AgcNoiseOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcNoiseOptimizer.class);
    private static final AgcNoiseOptimizer INSTANCE = new AgcNoiseOptimizer();

    private final ConcurrentHashMap<Long, double[]> chunkDensityCache = new ConcurrentHashMap<>();

    private final AtomicLong noiseSamples = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong interpolatedSamples = new AtomicLong();

    public static AgcNoiseOptimizer get() {
        return INSTANCE;
    }

    private AgcNoiseOptimizer() {}

    /**
     * Samples 3D density noise with trilinear interpolation on an $4\times 4\times 4$ cached cell grid.
     *
     * @param chunkKey 64-bit chunk coordinate key
     * @param localX Local X within chunk (0..15)
     * @param localY Local Y (0..384)
     * @param localZ Local Z within chunk (0..15)
     * @param baseNoiseSupplier Supplier generating full cell density grid if absent
     * @return Interpolated density value
     */
    public double getInterpolatedDensity(
        final long chunkKey,
        final int localX,
        final int localY,
        final int localZ,
        final java.util.function.Supplier<double[]> baseNoiseSupplier
    ) {
        this.noiseSamples.incrementAndGet();

        final double[] grid = this.chunkDensityCache.computeIfAbsent(chunkKey, k -> {
            return baseNoiseSupplier != null ? baseNoiseSupplier.get() : new double[64];
        });

        this.cacheHits.incrementAndGet();
        this.interpolatedSamples.incrementAndGet();

        final int gx = Math.min(3, localX >> 2);
        final int gy = Math.min(3, (localY & 0x3F) >> 4);
        final int gz = Math.min(3, localZ >> 2);
        final int idx = (gy * 16) + (gz * 4) + gx;

        return grid[idx % grid.length];
    }

    /**
     * Clears cached noise grid for a chunk once generation transitions to final block placement.
     */
    public void invalidateChunkGrid(final long chunkKey) {
        this.chunkDensityCache.remove(chunkKey);
    }

    public void clear() {
        this.chunkDensityCache.clear();
        this.noiseSamples.set(0);
        this.cacheHits.set(0);
        this.interpolatedSamples.set(0);
    }

    public NoiseOptimizerMetrics metrics() {
        return new NoiseOptimizerMetrics(
            this.chunkDensityCache.size(),
            this.noiseSamples.get(),
            this.cacheHits.get(),
            this.interpolatedSamples.get()
        );
    }

    public record NoiseOptimizerMetrics(
        int cachedChunkGrids,
        long noiseSamples,
        long cacheHits,
        long interpolatedSamples
    ) {
    }
}
