package io.papermc.paper.agc.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AgcEmptyChunkOptimizerTest {

    @Test
    public void testOptimizer() {
        AgcEmptyChunkOptimizer optimizer = AgcEmptyChunkOptimizer.get();
        boolean optimized = optimizer.optimizeEmptyChunk(12345L);
        assertFalse(optimized, "Mock returns false");
        assertEquals(0L, optimizer.getDeduplicatedCount());
    }
}
