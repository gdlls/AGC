package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.List;

/**
 * AGC (Advanced Game Core) — Performance tuning constants and safety classifications.
 */
public final class AgcPerformanceTuning {

    private AgcPerformanceTuning() {}

    /**
     * Safety classification used by {@link AgcCapabilityMatrix} to control feature enablement.
     */
    public enum Safety {
        VANILLA_SAFE,
        BASELINE,
        AGGRESSIVE_BUT_SAFE,
        EXPERIMENTAL
    }

    public static final Safety MAX_CHUNKS_SENT_PER_TICK_SAFETY = Safety.BASELINE;
    public static final int MAX_CHUNKS_SENT_PER_TICK = 32;

    public static final Safety CHANNEL_WATERMARK_SAFETY = Safety.VANILLA_SAFE;
    public static final int CHANNEL_AUTO_READ_LOW_WATERMARK = 1 << 16;
    public static final int CHANNEL_AUTO_READ_HIGH_WATERMARK = 1 << 20;

    public static final Safety CHANNEL_READ_TIMEOUT_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final int CHANNEL_READ_TIMEOUT_SECONDS = 30;

    public static final Safety USE_ZSTD_COMPRESSION_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final boolean USE_ZSTD_COMPRESSION = true;

    public static final Safety COMPRESSION_THRESHOLD_SAFETY = Safety.BASELINE;
    public static final int COMPRESSION_THRESHOLD = 256;

    public static final Safety COMPRESSION_LEVEL_SAFETY = Safety.BASELINE;
    public static final int COMPRESSION_LEVEL = 3;

    public static final Safety DEFAULT_VIEW_DISTANCE_SAFETY = Safety.VANILLA_SAFE;
    public static final int DEFAULT_SERVER_VIEW_DISTANCE = 12;
    public static final int DEFAULT_SERVER_SIMULATION_DISTANCE = 8;

    public static final Safety CHUNK_LOADS_PER_TICK_SAFETY = Safety.BASELINE;
    public static final int CHUNK_LOADS_PER_TICK = 16;

    public static final Safety UNLOAD_QUEUE_DRAIN_PER_TICK_SAFETY = Safety.BASELINE;
    public static final int UNLOAD_QUEUE_DRAIN_PER_TICK = 64;

    public static final Safety EAGER_SAVE_CHUNKS_PER_TICK_SAFETY = Safety.BASELINE;
    public static final int EAGER_SAVE_CHUNKS_PER_TICK = 32;

    public static final Safety CHUNK_PACKET_CACHE_SAFETY = Safety.BASELINE;
    public static final boolean CACHE_CHUNK_PACKETS = true;

    public static final int CHUNK_PACKET_CACHE_MAX_ENTRIES = 1024;

    public static final long CHUNK_PACKET_CACHE_MAX_BYTES = 64L * 1024L * 1024L;

    public static final int CHUNK_PACKET_CACHE_EVICT_SAMPLE_SIZE = 16;

    public static final int CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_NUMERATOR = 1;
    public static final int CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_DENOM = 4;

    public static final Safety ENTITY_TRACKING_INTERVAL_SAFETY = Safety.BASELINE;
    public static final int ENTITY_TRACKING_BASE_INTERVAL = 1;
    public static final int ENTITY_TRACKING_DISTANT_INTERVAL = 2;

    public static final int ENTITY_VIEW_SOFT_CAP = 200;

    public static final int PATHFINDING_BATCH_SIZE = 16;

    public static final Safety INACTIVE_WORLD_UNLOAD_DELAY_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final int INACTIVE_WORLD_UNLOAD_DELAY_TICKS = 20 * 30;

    public static final int WORLD_REGION_CHUNK_SIZE = 8;

    public static final double GLOBAL_REGION_THREAD_POOL_RATIO = 0.75;

    public static final Safety USE_HOT_OBJECT_POOLS_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final boolean USE_HOT_OBJECT_POOLS = true;

    public static final boolean ANTIXRAY_DEFAULT_ENABLED = false;

    public static final int PLAYER_PACKET_BATCH_SIZE = 32;

    public static final int BLOCK_UPDATE_BROADCAST_PER_TICK = 256;

    public static final Safety PACKET_PRIORITY_BUDGET_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final boolean USE_PACKET_PRIORITY_BUDGET = true;

    public static final long PACKET_PRIORITY_DEADLINE_MS = 50L;

    public static final int PACKET_PRIORITY_HIGH_BUDGET = 256 * 1024;
    public static final int PACKET_PRIORITY_NORMAL_BUDGET = 128 * 1024;
    public static final int PACKET_PRIORITY_LOW_BUDGET = 64 * 1024;

    public static final Safety WORLD_HIBERNATION_SAFETY = Safety.BASELINE;
    public static final int WORLD_HIBERNATION_GRACE_PERIOD_TICKS = 100;

    public static final Safety SINGLEPLAYER_FEEL_COMBAT_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final boolean DEFAULT_SINGLEPLAYER_FEEL_COMBAT = false;

    public static List<String> validate() {
        final List<String> issues = new ArrayList<>();
        if (MAX_CHUNKS_SENT_PER_TICK <= 0) {
            issues.add("MAX_CHUNKS_SENT_PER_TICK must be > 0");
        }
        if (CHANNEL_AUTO_READ_LOW_WATERMARK <= 0 || CHANNEL_AUTO_READ_HIGH_WATERMARK <= 0) {
            issues.add("Channel watermarks must be > 0");
        }
        if (CHANNEL_AUTO_READ_LOW_WATERMARK > CHANNEL_AUTO_READ_HIGH_WATERMARK) {
            issues.add("CHANNEL_AUTO_READ_LOW_WATERMARK must be <= HIGH");
        }
        if (CHANNEL_READ_TIMEOUT_SECONDS <= 0) {
            issues.add("CHANNEL_READ_TIMEOUT_SECONDS must be > 0");
        }
        if (COMPRESSION_THRESHOLD < 0) {
            issues.add("COMPRESSION_THRESHOLD must be >= 0");
        }
        if (CHUNK_PACKET_CACHE_MAX_ENTRIES < 0) {
            issues.add("CHUNK_PACKET_CACHE_MAX_ENTRIES must be >= 0");
        }
        if (CHUNK_PACKET_CACHE_MAX_BYTES < 0) {
            issues.add("CHUNK_PACKET_CACHE_MAX_BYTES must be >= 0");
        }
        if (CHUNK_PACKET_CACHE_EVICT_SAMPLE_SIZE <= 0) {
            issues.add("CHUNK_PACKET_CACHE_EVICT_SAMPLE_SIZE must be > 0");
        }
        if (CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_NUMERATOR <= 0
            || CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_DENOM <= 0
            || CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_NUMERATOR > CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_DENOM) {
            issues.add("Eviction batch ratio must be in (0, 1]");
        }
        if (GLOBAL_REGION_THREAD_POOL_RATIO <= 0.0 || GLOBAL_REGION_THREAD_POOL_RATIO > 1.0) {
            issues.add("GLOBAL_REGION_THREAD_POOL_RATIO must be in (0, 1]");
        }
        if (DEFAULT_SERVER_VIEW_DISTANCE < 2) {
            issues.add("DEFAULT_SERVER_VIEW_DISTANCE must be >= 2");
        }
        if (DEFAULT_SERVER_SIMULATION_DISTANCE < 2) {
            issues.add("DEFAULT_SERVER_SIMULATION_DISTANCE must be >= 2");
        }
        if (PACKET_PRIORITY_DEADLINE_MS < 1L || PACKET_PRIORITY_DEADLINE_MS > 1000L) {
            issues.add("PACKET_PRIORITY_DEADLINE_MS out of range [1, 1000]");
        }
        return issues;
    }
}
