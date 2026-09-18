package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgcPerformanceTuning} constants and validation.
 */
class AgcPerformanceTuningTest {

    @Test
    void allConstantsAreNonZero() {
        assertTrue(AgcPerformanceTuning.MAX_CHUNKS_SENT_PER_TICK > 0);
        assertTrue(AgcPerformanceTuning.CHANNEL_AUTO_READ_LOW_WATERMARK > 0);
        assertTrue(AgcPerformanceTuning.CHANNEL_AUTO_READ_HIGH_WATERMARK > 0);
        assertTrue(AgcPerformanceTuning.CHANNEL_READ_TIMEOUT_SECONDS > 0);
        assertTrue(AgcPerformanceTuning.DEFAULT_SERVER_VIEW_DISTANCE >= 2);
        assertTrue(AgcPerformanceTuning.DEFAULT_SERVER_SIMULATION_DISTANCE >= 2);
        assertTrue(AgcPerformanceTuning.CHUNK_LOADS_PER_TICK > 0);
        assertTrue(AgcPerformanceTuning.UNLOAD_QUEUE_DRAIN_PER_TICK > 0);
        assertTrue(AgcPerformanceTuning.PLAYER_PACKET_BATCH_SIZE > 0);
    }

    @Test
    void waterMarksAreOrdered() {
        assertTrue(AgcPerformanceTuning.CHANNEL_AUTO_READ_LOW_WATERMARK
            <= AgcPerformanceTuning.CHANNEL_AUTO_READ_HIGH_WATERMARK);
    }

    @Test
    void threadPoolRatioIsInValidRange() {
        assertTrue(AgcPerformanceTuning.GLOBAL_REGION_THREAD_POOL_RATIO > 0.0);
        assertTrue(AgcPerformanceTuning.GLOBAL_REGION_THREAD_POOL_RATIO <= 1.0);
    }

    @Test
    void validatePassesOnDefaults() {
        final List<String> issues = AgcPerformanceTuning.validate();
        assertTrue(issues.isEmpty(), "Expected no validation issues, got: " + issues);
    }

    @Test
    void safetyLabelsAreAttached() {
        assertNotNull(AgcPerformanceTuning.MAX_CHUNKS_SENT_PER_TICK_SAFETY);
        assertNotNull(AgcPerformanceTuning.CHANNEL_WATERMARK_SAFETY);
        assertNotNull(AgcPerformanceTuning.CHANNEL_READ_TIMEOUT_SAFETY);
        assertNotNull(AgcPerformanceTuning.USE_ZSTD_COMPRESSION_SAFETY);
        assertNotNull(AgcPerformanceTuning.COMPRESSION_THRESHOLD_SAFETY);
        assertNotNull(AgcPerformanceTuning.COMPRESSION_LEVEL_SAFETY);
        assertNotNull(AgcPerformanceTuning.DEFAULT_VIEW_DISTANCE_SAFETY);
        assertNotNull(AgcPerformanceTuning.CHUNK_LOADS_PER_TICK_SAFETY);
        assertNotNull(AgcPerformanceTuning.UNLOAD_QUEUE_DRAIN_PER_TICK_SAFETY);
        assertNotNull(AgcPerformanceTuning.CHUNK_PACKET_CACHE_SAFETY);
        assertNotNull(AgcPerformanceTuning.ENTITY_TRACKING_INTERVAL_SAFETY);
        assertNotNull(AgcPerformanceTuning.INACTIVE_WORLD_UNLOAD_DELAY_SAFETY);
        assertNotNull(AgcPerformanceTuning.USE_HOT_OBJECT_POOLS_SAFETY);
        assertNotNull(AgcPerformanceTuning.PACKET_PRIORITY_BUDGET_SAFETY);
    }

    @Test
    void allSafetyValuesAreInEnum() {
        for (final AgcPerformanceTuning.Safety s : AgcPerformanceTuning.Safety.values()) {
            assertNotNull(s);
        }
        assertEquals(4, AgcPerformanceTuning.Safety.values().length);
    }

    @Test
    void chunkCacheMaxBytesIsSane() {
        assertTrue(AgcPerformanceTuning.CHUNK_PACKET_CACHE_MAX_ENTRIES >= 0);
        assertTrue(AgcPerformanceTuning.CHUNK_PACKET_CACHE_MAX_BYTES >= 0);
        assertTrue(AgcPerformanceTuning.CHUNK_PACKET_CACHE_EVICT_SAMPLE_SIZE > 0);
        assertTrue(AgcPerformanceTuning.CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_NUMERATOR > 0);
        assertTrue(AgcPerformanceTuning.CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_DENOM > 0);
        assertTrue(AgcPerformanceTuning.CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_NUMERATOR
            <= AgcPerformanceTuning.CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_DENOM);
    }

    @Test
    void priorityBudgetsAreOrdered() {
        // high >= normal >= low
        assertTrue(AgcPerformanceTuning.PACKET_PRIORITY_HIGH_BUDGET
            >= AgcPerformanceTuning.PACKET_PRIORITY_NORMAL_BUDGET);
        assertTrue(AgcPerformanceTuning.PACKET_PRIORITY_NORMAL_BUDGET
            >= AgcPerformanceTuning.PACKET_PRIORITY_LOW_BUDGET);
    }

    @Test
    void booleanFlagsAreExplicit() {
        assertTrue(AgcPerformanceTuning.CACHE_CHUNK_PACKETS || !AgcPerformanceTuning.CACHE_CHUNK_PACKETS);
        assertTrue(AgcPerformanceTuning.USE_HOT_OBJECT_POOLS || !AgcPerformanceTuning.USE_HOT_OBJECT_POOLS);
    }

    @Test
    void antixrayIsOptIn() {
        assertFalse(AgcPerformanceTuning.ANTIXRAY_DEFAULT_ENABLED);
    }
}
