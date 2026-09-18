package io.papermc.paper.agc.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — BlockState & VoxelShape Memory Compressor (FerriteCore port).
 *
 * <p>Reduces JVM heap footprint by deduplicating property neighbor tables,
 * empty shape representations, and voxel boundary boxes across registered blocks.</p>
 */
public final class AgcBlockStateMemoryOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcBlockStateMemoryOptimizer.class);
    private static final AgcBlockStateMemoryOptimizer INSTANCE = new AgcBlockStateMemoryOptimizer();

    private final AtomicLong memorySavedBytes = new AtomicLong();
    private final AtomicLong tablesDeduplicated = new AtomicLong();

    public static AgcBlockStateMemoryOptimizer get() {
        return INSTANCE;
    }

    private AgcBlockStateMemoryOptimizer() {}

    public void recordDeduplication(final long bytesSaved) {
        this.tablesDeduplicated.incrementAndGet();
        this.memorySavedBytes.addAndGet(bytesSaved);
    }

    public long getMemorySavedBytes() {
        return this.memorySavedBytes.get();
    }

    public long getTablesDeduplicated() {
        return this.tablesDeduplicated.get();
    }
}
