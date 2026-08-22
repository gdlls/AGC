package io.papermc.paper.agc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — 통합 메트릭 accessor. spark / Timings 폴링에서 사용.
 *
 * <p>다른 AGC 컴포넌트들 (Folia tuning, HotPathCache, NetworkEnhancer)에서
 * 메트릭을 한 곳에 모아서 외부 도구가 한 번의 호출로 전부 가져갈 수 있도록 한다.</p>
 *
 * <p>lock-free counter만 사용. 스냅샷 시점의 일관성은 보장되지 않지만
 * Timings의 ms-주기 폴링에는 충분.</p>
 */
public final class AgcMetrics {

    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong CACHE_MISSES = new AtomicLong();
    private static final AtomicLong CACHE_EVICTIONS = new AtomicLong();
    private static final AtomicLong PACKET_BYTES_SENT = new AtomicLong();
    private static final AtomicLong PACKET_BYTES_COMPRESSED = new AtomicLong();
    private static final AtomicLong BUDGET_THROTTLED = new AtomicLong();
    private static final AtomicLong ASYNC_TASKS_SUBMITTED = new AtomicLong();
    private static final AtomicLong ASYNC_TASKS_REJECTED = new AtomicLong();
    private static final AtomicLong ASYNC_TASKS_FAILED = new AtomicLong();
    private static final AtomicLong TICKS_RECORDED = new AtomicLong();
    private static final AtomicLong CHANNELS_REGISTERED = new AtomicLong();
    private static final AtomicLong CHANNELS_CLOSED = new AtomicLong();

    private AgcMetrics() {}

    // =====================================================================
    // Recording API — 내부 AGC 컴포넌트들이 호출
    // =====================================================================

    public static void recordCacheHit() { CACHE_HITS.incrementAndGet(); }
    public static void recordCacheMiss() { CACHE_MISSES.incrementAndGet(); }
    public static void recordCacheEviction(final int count) {
        if (count > 0) {
            CACHE_EVICTIONS.addAndGet(count);
        }
    }
    public static void recordPacketSent(final long bytes) {
        if (bytes > 0) {
            PACKET_BYTES_SENT.addAndGet(bytes);
        }
    }
    public static void recordPacketCompressed(final long bytes) {
        if (bytes > 0) {
            PACKET_BYTES_COMPRESSED.addAndGet(bytes);
        }
    }
    public static void recordBudgetThrottled() { BUDGET_THROTTLED.incrementAndGet(); }
    public static void recordAsyncSubmitted() { ASYNC_TASKS_SUBMITTED.incrementAndGet(); }
    public static void recordAsyncRejected() { ASYNC_TASKS_REJECTED.incrementAndGet(); }
    public static void recordAsyncFailed() { ASYNC_TASKS_FAILED.incrementAndGet(); }
    public static void recordTick() { TICKS_RECORDED.incrementAndGet(); }
    public static void recordChannelRegistered() { CHANNELS_REGISTERED.incrementAndGet(); }
    public static void recordChannelClosed() { CHANNELS_CLOSED.incrementAndGet(); }

    // =====================================================================
    // Accessor API — spark / Timings 폴링
    // =====================================================================

    /**
     * 현재 카운터 스냅샷. Timings의 {@code onTick} 등에서 호출.
     */
    public static Snapshot snapshot() {
        return new Snapshot(
            System.nanoTime(),
            CACHE_HITS.get(),
            CACHE_MISSES.get(),
            CACHE_EVICTIONS.get(),
            PACKET_BYTES_SENT.get(),
            PACKET_BYTES_COMPRESSED.get(),
            BUDGET_THROTTLED.get(),
            ASYNC_TASKS_SUBMITTED.get(),
            ASYNC_TASKS_REJECTED.get(),
            ASYNC_TASKS_FAILED.get(),
            TICKS_RECORDED.get(),
            CHANNELS_REGISTERED.get(),
            CHANNELS_CLOSED.get()
        );
    }

    /**
     * 운영자가 보는 human-readable 보고.
     */
    public static String report() {
        final Snapshot s = snapshot();
        final StringBuilder out = new StringBuilder(512);
        out.append("AGC metrics:\n");
        out.append("  uptime=").append(formatNanos(s.nanoTime())).append('\n');
        out.append("  cache: hits=").append(s.cacheHits)
            .append(" misses=").append(s.cacheMisses)
            .append(" hitRatio=").append(String.format("%.2f%%", s.cacheHitRatio() * 100.0))
            .append(" evictions=").append(s.cacheEvictions).append('\n');
        out.append("  network: bytesSent=").append(s.packetBytesSent)
            .append(" bytesCompressed=").append(s.packetBytesCompressed)
            .append(" compressionRatio=").append(String.format("%.2f%%", s.compressionRatio() * 100.0))
            .append('\n');
        out.append("  budgets: throttled=").append(s.budgetThrottled).append('\n');
        out.append("  async: submitted=").append(s.asyncTasksSubmitted)
            .append(" rejected=").append(s.asyncTasksRejected)
            .append(" failed=").append(s.asyncTasksFailed).append('\n');
        out.append("  ticks: recorded=").append(s.ticksRecorded).append('\n');
        out.append("  channels: registered=").append(s.channelsRegistered)
            .append(" closed=").append(s.channelsClosed)
            .append(" live=").append(s.liveChannels()).append('\n');
        return out.toString();
    }

    /**
     * Timings 메타데이터용 {@code Map<String, Long>}. 키는 Timings 패널 이름.
     */
    public static Map<String, Long> asTimingsMap() {
        final Snapshot s = snapshot();
        final Map<String, Long> map = new LinkedHashMap<>(16);
        map.put("agc.cache.hits", s.cacheHits);
        map.put("agc.cache.misses", s.cacheMisses);
        map.put("agc.cache.evictions", s.cacheEvictions);
        map.put("agc.cache.hit_ratio_bp", Math.round(s.cacheHitRatio() * 10000.0));
        map.put("agc.net.bytes_sent", s.packetBytesSent);
        map.put("agc.net.bytes_compressed", s.packetBytesCompressed);
        map.put("agc.net.compression_ratio_bp", Math.round(s.compressionRatio() * 10000.0));
        map.put("agc.budget.throttled", s.budgetThrottled);
        map.put("agc.async.submitted", s.asyncTasksSubmitted);
        map.put("agc.async.rejected", s.asyncTasksRejected);
        map.put("agc.async.failed", s.asyncTasksFailed);
        map.put("agc.ticks.recorded", s.ticksRecorded);
        map.put("agc.channels.registered", s.channelsRegistered);
        map.put("agc.channels.closed", s.channelsClosed);
        map.put("agc.channels.live", (long) s.liveChannels());
        return map;
    }

    /**
     * 모든 카운터 reset. 테스트 / 서버 재시작 시 사용.
     */
    public static void resetAll() {
        CACHE_HITS.set(0);
        CACHE_MISSES.set(0);
        CACHE_EVICTIONS.set(0);
        PACKET_BYTES_SENT.set(0);
        PACKET_BYTES_COMPRESSED.set(0);
        BUDGET_THROTTLED.set(0);
        ASYNC_TASKS_SUBMITTED.set(0);
        ASYNC_TASKS_REJECTED.set(0);
        ASYNC_TASKS_FAILED.set(0);
        TICKS_RECORDED.set(0);
        CHANNELS_REGISTERED.set(0);
        CHANNELS_CLOSED.set(0);
    }

    private static String formatNanos(final long nanos) {
        if (nanos < 1_000_000L) return nanos + "ns";
        if (nanos < 1_000_000_000L) return String.format("%.2fms", nanos / 1_000_000.0);
        if (nanos < 60L * 1_000_000_000L) return String.format("%.2fs", nanos / 1_000_000_000.0);
        return String.format("%.2fm", nanos / (60.0 * 1_000_000_000.0));
    }

    /**
     * 메트릭 스냅샷. record 호출 사이의 일관성 보장은 없지만 ms 주기 폴링에는 충분.
     */
    public record Snapshot(
        long nanoTime,
        long cacheHits,
        long cacheMisses,
        long cacheEvictions,
        long packetBytesSent,
        long packetBytesCompressed,
        long budgetThrottled,
        long asyncTasksSubmitted,
        long asyncTasksRejected,
        long asyncTasksFailed,
        long ticksRecorded,
        long channelsRegistered,
        long channelsClosed
    ) {
        public double cacheHitRatio() {
            final long total = this.cacheHits + this.cacheMisses;
            if (total == 0L) return 0.0;
            return (double) this.cacheHits / (double) total;
        }

        public double compressionRatio() {
            if (this.packetBytesSent == 0L) return 0.0;
            return (double) this.packetBytesCompressed / (double) this.packetBytesSent;
        }

        public int liveChannels() {
            return (int) Math.max(0L, this.channelsRegistered - this.channelsClosed);
        }
    }
}
