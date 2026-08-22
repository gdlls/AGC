package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgcMetrics} — pure Java, no external deps.
 */
class AgcMetricsTest {

    @BeforeEach
    void resetMetrics() {
        AgcMetrics.resetAll();
    }

    @Test
    void cacheHitMissCounters() {
        AgcMetrics.recordCacheHit();
        AgcMetrics.recordCacheHit();
        AgcMetrics.recordCacheMiss();
        final AgcMetrics.Snapshot s = AgcMetrics.snapshot();
        assertEquals(2L, s.cacheHits());
        assertEquals(1L, s.cacheMisses());
    }

    @Test
    void cacheEvictionCounterAccumulates() {
        AgcMetrics.recordCacheEviction(5);
        AgcMetrics.recordCacheEviction(3);
        assertEquals(8L, AgcMetrics.snapshot().cacheEvictions());
    }

    @Test
    void cacheEvictionIgnoresZeroAndNegative() {
        AgcMetrics.recordCacheEviction(0);
        AgcMetrics.recordCacheEviction(-1);
        assertEquals(0L, AgcMetrics.snapshot().cacheEvictions());
    }

    @Test
    void packetByteCounters() {
        AgcMetrics.recordPacketSent(1000L);
        AgcMetrics.recordPacketCompressed(700L);
        final AgcMetrics.Snapshot s = AgcMetrics.snapshot();
        assertEquals(1000L, s.packetBytesSent());
        assertEquals(700L, s.packetBytesCompressed());
    }

    @Test
    void packetSentIgnoresNegative() {
        AgcMetrics.recordPacketSent(-1L);
        assertEquals(0L, AgcMetrics.snapshot().packetBytesSent());
    }

    @Test
    void budgetThrottledCounter() {
        AgcMetrics.recordBudgetThrottled();
        AgcMetrics.recordBudgetThrottled();
        assertEquals(2L, AgcMetrics.snapshot().budgetThrottled());
    }

    @Test
    void asyncTaskCounters() {
        AgcMetrics.recordAsyncSubmitted();
        AgcMetrics.recordAsyncSubmitted();
        AgcMetrics.recordAsyncSubmitted();
        AgcMetrics.recordAsyncRejected();
        AgcMetrics.recordAsyncFailed();
        final AgcMetrics.Snapshot s = AgcMetrics.snapshot();
        assertEquals(3L, s.asyncTasksSubmitted());
        assertEquals(1L, s.asyncTasksRejected());
        assertEquals(1L, s.asyncTasksFailed());
    }

    @Test
    void tickAndChannelCounters() {
        for (int i = 0; i < 20; i++) {
            AgcMetrics.recordTick();
        }
        for (int i = 0; i < 5; i++) {
            AgcMetrics.recordChannelRegistered();
        }
        for (int i = 0; i < 2; i++) {
            AgcMetrics.recordChannelClosed();
        }
        final AgcMetrics.Snapshot s = AgcMetrics.snapshot();
        assertEquals(20L, s.ticksRecorded());
        assertEquals(5L, s.channelsRegistered());
        assertEquals(2L, s.channelsClosed());
        assertEquals(3, s.liveChannels());
    }

    @Test
    void cacheHitRatioIsZeroOnNoTraffic() {
        assertEquals(0.0, AgcMetrics.snapshot().cacheHitRatio());
    }

    @Test
    void cacheHitRatioIsCorrect() {
        AgcMetrics.recordCacheHit();
        AgcMetrics.recordCacheHit();
        AgcMetrics.recordCacheHit();
        AgcMetrics.recordCacheMiss();
        assertEquals(0.75, AgcMetrics.snapshot().cacheHitRatio(), 0.001);
    }

    @Test
    void compressionRatioIsCorrect() {
        AgcMetrics.recordPacketSent(1000L);
        AgcMetrics.recordPacketCompressed(600L);
        assertEquals(0.6, AgcMetrics.snapshot().compressionRatio(), 0.001);
    }

    @Test
    void liveChannelsCannotGoNegative() {
        AgcMetrics.recordChannelClosed();
        AgcMetrics.recordChannelClosed();
        assertEquals(0, AgcMetrics.snapshot().liveChannels());
    }

    @Test
    void reportContainsKeyMetrics() {
        AgcMetrics.recordCacheHit();
        AgcMetrics.recordPacketSent(100L);
        final String report = AgcMetrics.report();
        assertNotNull(report);
        assertTrue(report.contains("AGC metrics"));
        assertTrue(report.contains("cache"));
        assertTrue(report.contains("network"));
    }

    @Test
    void asTimingsMapContainsExpectedKeys() {
        AgcMetrics.recordCacheHit();
        final Map<String, Long> map = AgcMetrics.asTimingsMap();
        assertNotNull(map);
        assertTrue(map.containsKey("agc.cache.hits"));
        assertTrue(map.containsKey("agc.cache.misses"));
        assertTrue(map.containsKey("agc.net.bytes_sent"));
        assertTrue(map.containsKey("agc.async.submitted"));
        assertTrue(map.containsKey("agc.channels.live"));
        assertEquals(1L, map.get("agc.cache.hits"));
    }

    @Test
    void resetAllZeroesEverything() {
        AgcMetrics.recordCacheHit();
        AgcMetrics.recordCacheMiss();
        AgcMetrics.recordPacketSent(1000L);
        AgcMetrics.recordTick();
        AgcMetrics.recordChannelRegistered();

        AgcMetrics.resetAll();

        final AgcMetrics.Snapshot s = AgcMetrics.snapshot();
        assertEquals(0L, s.cacheHits());
        assertEquals(0L, s.cacheMisses());
        assertEquals(0L, s.packetBytesSent());
        assertEquals(0L, s.ticksRecorded());
        assertEquals(0L, s.channelsRegistered());
    }

    @Test
    void snapshotNanoTimeIsPositive() {
        final AgcMetrics.Snapshot s = AgcMetrics.snapshot();
        assertTrue(s.nanoTime() > 0L);
    }
}
