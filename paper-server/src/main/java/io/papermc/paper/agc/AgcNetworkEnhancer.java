package io.papermc.paper.agc;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.papermc.paper.network.ChannelInitializeListener;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Netty 채널 파이프라인 부스터.
 *
 * <p>Paper의 {@link ChannelInitializeListener} 훅을 통해, Connection이 자제적으로
 * Netty 파이프라인을 구성한 직후에 AGC가 채널의 워터마크, idle timeout, auto-read 정책 등을
 * 하드코딩 튜닝합니다.</p>
 *
 * <p>이 클래스는 <b>paper-specific</b> 후처리 훅이라, patches/sources의 Connection 패치를
 * 직접 수정하지 않고도 채널 레벨의 핵심 튜닝을 적용할 수 있습니다.</p>
 *
 * <h2>활성화 / 비활성화</h2>
 * <p>{@link #setEnabled(boolean)}로 런타임에 토글 가능. 비활성화되면
 * {@code afterInitChannel}이 no-op으로 바뀌지만 이미 등록된 채널은 영향받지 않는다.
 * 새 연결부터 적용.</p>
 *
 * <h2>스레드 안전성</h2>
 * <p>등록 / 해제 모두 Netty I/O 스레드 또는 부트스트랩 스레드에서 호출된다.
 * 라이브 채널 트래킹은 {@link ConcurrentHashMap} 키셋 사용.</p>
 */
public final class AgcNetworkEnhancer implements ChannelInitializeListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcNetworkEnhancer.class);
    private static final AgcNetworkEnhancer INSTANCE = new AgcNetworkEnhancer();

    /** 채널 ID → 등록 정보 추적. closeFuture에서 자동 제거. */
    private final ConcurrentHashMap<Channel, ChannelInfo> liveChannels = new ConcurrentHashMap<>();

    /** 메트릭 — 전체 적용 / 스킵 카운트. */
    private final AtomicLong appliedCount = new AtomicLong();
    private final AtomicLong skippedCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();

    private volatile boolean enabled = true;

    public static AgcNetworkEnhancer get() {
        return INSTANCE;
    }

    private AgcNetworkEnhancer() {}

    /**
     * 런타임 토글. true면 새 채널에 적용, false면 no-op.
     */
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        LOGGER.info("AGC Netty enhancer {}", enabled ? "enabled" : "disabled");
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Netty 채널이 만들어진 직후 호출. 워터마크/timeout/auto-read 하드코딩.
     */
    @Override
    public void afterInitChannel(@NonNull final Channel channel) {
        if (!this.enabled) {
            this.skippedCount.incrementAndGet();
            return;
        }
        final long startNanos = System.nanoTime();
        try {
            applyWaterMark(channel);
            applyReadTimeout(channel);
            applyAutoReadPolicy(channel);
            final ChannelInfo info = new ChannelInfo(System.identityHashCode(channel), startNanos);
            this.liveChannels.put(channel, info);
            channel.closeFuture().addListener(future -> this.liveChannels.remove(channel));
            this.appliedCount.incrementAndGet();
        } catch (final Throwable t) {
            // 절대 메인 흐름에 영향 주면 안 됨.
            this.errorCount.incrementAndGet();
            LOGGER.warn("Failed to apply AGC Netty tuning on channel {}", channel, t);
        }
    }

    private static void applyWaterMark(final Channel channel) {
        // AGC 튜닝: 더 큰 high-water-mark → auto-read가 자주 토글되지 않음 → syscall 감소.
        // Netty 4.2: public 생성자는 2-arg (low, high)만 노출. 3-arg deprecated/internal.
        final WriteBufferWaterMark mark = new WriteBufferWaterMark(
            AgcPerformanceTuning.CHANNEL_AUTO_READ_LOW_WATERMARK,
            AgcPerformanceTuning.CHANNEL_AUTO_READ_HIGH_WATERMARK
        );
        channel.config().setWriteBufferWaterMark(mark);
    }

    private static void applyReadTimeout(final Channel channel) {
        // read timeout — 네트워크 헬스 체크. AGC 상수에서 가져옴.
        final ChannelPipeline pipeline = channel.pipeline();
        if (!pipeline.names().contains(READ_TIMEOUT_HANDLER_NAME)) {
            pipeline.addFirst(READ_TIMEOUT_HANDLER_NAME,
                new ReadTimeoutHandler(AgcPerformanceTuning.CHANNEL_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    private static void applyAutoReadPolicy(final Channel channel) {
        // auto-read는 기본 true. 채널 핸들러가 직접 false로 끌 수 있도록 그대로 두지만,
        // 초기 채널은 true로 강제하여 첫 패킷(login start)을 즉시 받게 함.
        channel.config().setAutoRead(true);
    }

    private static final String READ_TIMEOUT_HANDLER_NAME = "agc-read-timeout";

    /**
     * 현재 등록된 live 채널 수.
     */
    public int liveChannelCount() {
        return this.liveChannels.size();
    }

    /**
     * 메트릭 accessor. spark / Timings 폴링이 사용.
     */
    public Metrics metrics() {
        return new Metrics(
            this.appliedCount.get(),
            this.skippedCount.get(),
            this.errorCount.get(),
            this.liveChannels.size()
        );
    }

    public record Metrics(long applied, long skipped, long errors, int liveChannels) {
    }

    private record ChannelInfo(int id, long registeredAtNanos) {
    }
}
