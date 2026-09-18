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
 * AGC Netty channel pipeline post-processor.
 * Applies tuned watermarks, read timeouts, and auto-read policies via {@link ChannelInitializeListener}.
 */
public final class AgcNetworkEnhancer implements ChannelInitializeListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcNetworkEnhancer.class);
    private static final AgcNetworkEnhancer INSTANCE = new AgcNetworkEnhancer();

    private final ConcurrentHashMap<Channel, ChannelInfo> liveChannels = new ConcurrentHashMap<>();

    private final AtomicLong appliedCount = new AtomicLong();
    private final AtomicLong skippedCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();

    private volatile boolean enabled = true;

    public static AgcNetworkEnhancer get() {
        return INSTANCE;
    }

    private AgcNetworkEnhancer() {}

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        LOGGER.info("AGC Netty enhancer {}", enabled ? "enabled" : "disabled");
    }

    public boolean isEnabled() {
        return this.enabled;
    }

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
            this.errorCount.incrementAndGet();
            LOGGER.warn("Failed to apply AGC Netty tuning on channel {}", channel, t);
        }
    }

    private static void applyWaterMark(final Channel channel) {
        // VANILLA mode promises untouched Netty channels: never touch watermarks there.
        if (AgcCapabilityMatrix.getMode() == AgcCapabilityMatrix.Mode.VANILLA) {
            return;
        }
        int low = AgcPerformanceTuning.CHANNEL_AUTO_READ_LOW_WATERMARK;
        int high = AgcPerformanceTuning.CHANNEL_AUTO_READ_HIGH_WATERMARK;
        try {
            if (AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_CHANNEL_WATERMARK)) {
                int players = 0;
                try {
                    players = org.bukkit.Bukkit.getOnlinePlayers().size();
                } catch (final Throwable ignored) {
                    players = 0;
                }
                final io.papermc.paper.agc.network.AgcUniverseNetEngine.ChannelTuning tuning =
                    io.papermc.paper.agc.network.AgcUniverseNetEngine.get()
                        .channelTuning(Math.max(1, Runtime.getRuntime().availableProcessors()), players);
                low = tuning.lowWatermarkBytes();
                high = tuning.highWatermarkBytes();
            }
        } catch (final Throwable ignored) {
            // fall back to static tuning below
        }
        final WriteBufferWaterMark mark = new WriteBufferWaterMark(low, high);
        channel.config().setWriteBufferWaterMark(mark);
    }

    private static void applyReadTimeout(final Channel channel) {
        // Explicit opt-in only (NETWORK_READ_TIMEOUT is AGGRESSIVE_BUT_SAFE): vanilla Paper
        // already installs its own configured read timeout, so baseline/VANILLA channels
        // keep the stock pipeline untouched.
        if (!AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_READ_TIMEOUT)) {
            return;
        }
        final ChannelPipeline pipeline = channel.pipeline();
        if (!pipeline.names().contains(READ_TIMEOUT_HANDLER_NAME)) {
            pipeline.addFirst(READ_TIMEOUT_HANDLER_NAME,
                new ReadTimeoutHandler(AgcPerformanceTuning.CHANNEL_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    private static void applyAutoReadPolicy(final Channel channel) {
        // UniverseNet staged admission check: if under a connection storm or tick pressure,
        // defer auto-read to staged queue so Netty doesn't flood CPU with VarInt decode and decompression.
        // When the feature is off, Netty's default auto-read (true) is left untouched.
        if (AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.UNIVERSE_NET_ENGINE)) {
            final boolean immediate = io.papermc.paper.agc.network.AgcUniverseNetEngine.get().onChannelInit(channel);
            if (immediate) {
                channel.config().setAutoRead(true);
            }
        }
    }

    private static final String READ_TIMEOUT_HANDLER_NAME = "agc-read-timeout";

    public int liveChannelCount() {
        return this.liveChannels.size();
    }

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
