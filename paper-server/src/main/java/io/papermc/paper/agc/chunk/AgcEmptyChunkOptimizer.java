package io.papermc.paper.agc.chunk;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Empty Chunk Optimizer.
 */
public final class AgcEmptyChunkOptimizer {
    private static final AgcEmptyChunkOptimizer INSTANCE = new AgcEmptyChunkOptimizer();

    private final AtomicLong deduplicatedChunks = new AtomicLong(0);

    public static AgcEmptyChunkOptimizer get() {
        return INSTANCE;
    }

    private AgcEmptyChunkOptimizer() {}

    public boolean optimizeEmptyChunk(long chunkPos) {
        return false;
    }

    public long getDeduplicatedCount() {
        return deduplicatedChunks.get();
    }
}
