package io.papermc.paper.agc;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Netty End-of-Tick Flush Coalescer.
 *
 * <p>By default, Netty channels perform an expensive {@code flush()} call (triggering kernel
 * system calls such as {@code writev}) on virtually every outbound packet. Under 500+ players,
 * this results in tens of thousands of syscalls per tick, causing heavy CPU context switching.</p>
 *
 * <p>This coalescer defers non-interactive packet writes into the channel's outbound buffer
 * and triggers a single, unified {@code flush()} per active connection at the end of each server tick,
 * while allowing critical interactive packets to flush immediately.</p>
 */
public final class AgcFlushCoalescer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcFlushCoalescer.class);
    private static final AgcFlushCoalescer INSTANCE = new AgcFlushCoalescer();

    private final ConcurrentHashMap<Channel, Boolean> pendingFlushChannels = new ConcurrentHashMap<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    // Telemetry & metrics
    private final AtomicLong packetsCoalesced = new AtomicLong();
    private final AtomicLong flushesExecuted = new AtomicLong();
    private final AtomicLong immediateFlushes = new AtomicLong();
    private final AtomicLong flushErrors = new AtomicLong();

    public static AgcFlushCoalescer get() {
        return INSTANCE;
    }

    private AgcFlushCoalescer() {}

    /**
     * Marks a channel as having pending buffered writes requiring an end-of-tick flush.
     *
     * @param channel The Netty channel
     */
    public void registerPendingFlush(final Channel channel) {
        if (channel == null || !this.enabled.get() || !channel.isActive()) {
            return;
        }
        this.packetsCoalesced.incrementAndGet();
        this.pendingFlushChannels.put(channel, Boolean.TRUE);
    }

    /**
     * Records an immediate flush bypass (e.g. for chat, combat, ping, or keep-alive packets).
     *
     * @param channel The Netty channel
     */
    public void recordImmediateFlush(final Channel channel) {
        this.immediateFlushes.incrementAndGet();
        if (channel != null) {
            this.pendingFlushChannels.remove(channel);
        }
    }

    /**
     * Flushes all pending channels at the end of the server tick.
     * Must be called at the conclusion of each tick loop before socket polling.
     *
     * @return Number of channels flushed in this pass
     */
    public int flushPendingChannels() {
        if (this.pendingFlushChannels.isEmpty()) {
            return 0;
        }

        int flushed = 0;
        for (final Channel channel : this.pendingFlushChannels.keySet()) {
            this.pendingFlushChannels.remove(channel);
            if (channel.isActive()) {
                try {
                    channel.flush();
                    flushed++;
                    this.flushesExecuted.incrementAndGet();
                } catch (final Throwable t) {
                    this.flushErrors.incrementAndGet();
                    LOGGER.warn("Failed to flush coalesced channel {}", channel, t);
                }
            }
        }
        return flushed;
    }

    /**
     * Clears all pending flush registrations (e.g. on server reload or shutdown).
     */
    public void clear() {
        this.pendingFlushChannels.clear();
    }

    public void setEnabled(final boolean enabled) {
        this.enabled.set(enabled);
    }

    public boolean isEnabled() {
        return this.enabled.get();
    }

    public int pendingChannelCount() {
        return this.pendingFlushChannels.size();
    }

    public void resetMetrics() {
        this.pendingFlushChannels.clear();
        this.packetsCoalesced.set(0);
        this.flushesExecuted.set(0);
        this.immediateFlushes.set(0);
        this.flushErrors.set(0);
    }

    public CoalesceMetrics metrics() {
        return new CoalesceMetrics(
            this.enabled.get(),
            this.packetsCoalesced.get(),
            this.flushesExecuted.get(),
            this.immediateFlushes.get(),
            this.flushErrors.get(),
            this.pendingFlushChannels.size()
        );
    }

    public record CoalesceMetrics(
        boolean enabled,
        long packetsCoalesced,
        long flushesExecuted,
        long immediateFlushes,
        long errors,
        int pendingChannels
    ) {
        public double batchingRatio() {
            if (this.flushesExecuted == 0) return 0.0;
            return (double) this.packetsCoalesced / (double) this.flushesExecuted;
        }
    }
}
