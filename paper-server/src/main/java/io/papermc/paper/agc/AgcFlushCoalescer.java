package io.papermc.paper.agc;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

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
 *
 * <h2>Hot-path cost (why this class looks unusual)</h2>
 * <p>{@link #registerPendingFlush(Channel, AtomicBoolean)} is reached from
 * {@code Connection.doSendPacket} for <b>every</b> non-immediate outbound packet, and that method is
 * always dispatched onto the channel's event loop. A JFR profile of a live 300-bot server attributed
 * ~3.2% of all JVM execution samples to the {@code ConcurrentHashMap.put} this used to perform per
 * packet, plus a further ~0.9% to {@code AbstractChannel.hashCode} (still {@code System.identityHashCode})
 * and share of {@code String.equals} in the map's key comparison.</p>
 *
 * <p>The fix keeps the exact same externally observable behaviour but performs <b>at most one map
 * write per channel per tick</b>. The map value is a per-connection {@link AtomicBoolean}
 * ("ticket"). The writer only reaches the map when the ticket transitions {@code false → true}, which
 * happens once per channel per tick, so all following packets cost a single uncontended CAS on an
 * object owned by the writer's own event loop.</p>
 *
 * <p>Correctness argument — the ticket has exactly one logical owner, the channel's event loop:</p>
 * <ul>
 *   <li>{@code Connection.sendPacket} dispatches {@code doSendPacket} onto the channel's event loop,
 *       so the {@code compareAndSet(false, true)} always runs on that loop.</li>
 *   <li>{@link #flushPendingChannels()} clears the ticket inside a task scheduled on that same event
 *       loop (or inline when the caller already is the loop, e.g. {@code EmbeddedChannel} tests).</li>
 *   <li>The flush task is queued <b>after</b> every write that was handed to the loop before it, so
 *       those writes are always covered by that flush; writes handed over after the task was queued
 *       observe {@code ticket == false} and register themselves for the next tick — exactly the
 *       behaviour before this change.</li>
 * </ul>
 * <p>Because the ticket is only mutated by the event loop, no cross-thread visibility window can
 * swallow a registration and strand buffered bytes in a channel forever.</p>
 */
public final class AgcFlushCoalescer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcFlushCoalescer.class);
    private static final AgcFlushCoalescer INSTANCE = new AgcFlushCoalescer();

    /**
     * Channels with buffered writes awaiting the end-of-tick flush.
     * The value is that channel's per-tick registration ticket.
     */
    private final ConcurrentHashMap<Channel, AtomicBoolean> pendingFlushChannels = new ConcurrentHashMap<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    // Telemetry & metrics. The per-packet counters are LongAdders: AtomicLong.incrementAndGet()
    // from four Netty event loops in parallel bounces one cache line between cores.
    private final LongAdder packetsCoalesced = new LongAdder();
    private final AtomicLong flushesExecuted = new AtomicLong();
    private final AtomicLong immediateFlushes = new AtomicLong();
    private final AtomicLong flushErrors = new AtomicLong();

    public static AgcFlushCoalescer get() {
        return INSTANCE;
    }

    private AgcFlushCoalescer() {}

    /**
     * Convenience overload for callers that have no reusable ticket and therefore always want to
     * register (test/one-shot paths). Production traffic should use
     * {@link #registerPendingFlush(Channel, AtomicBoolean)} with a connection-owned ticket.
     *
     * @param channel The Netty channel
     */
    public void registerPendingFlush(final Channel channel) {
        this.registerPendingFlush(channel, new AtomicBoolean(false));
    }

    /**
     * Marks a channel as having pending buffered writes requiring an end-of-tick flush.
     *
     * <p>Only the first call per channel per tick performs a map write; later calls just flip the
     * (event-loop-owned) ticket. See the class Javadoc for the correctness argument.</p>
     *
     * @param channel The Netty channel
     * @param ticket The channel's per-tick registration ticket, owned by the channel's event loop
     */
    public void registerPendingFlush(final Channel channel, final AtomicBoolean ticket) {
        if (channel == null || ticket == null || !this.enabled.get() || !channel.isActive()) {
            return;
        }
        // VANILLA mode promises untouched packet flushing: deferring flushes to tick end
        // must never apply there, regardless of preset/config state.
        if (AgcCapabilityMatrix.getMode() == AgcCapabilityMatrix.Mode.VANILLA) {
            return;
        }
        this.packetsCoalesced.increment();
        if (ticket.compareAndSet(false, true)) {
            this.pendingFlushChannels.put(channel, ticket);
        }
    }

    /**
     * Records an immediate flush bypass (e.g. for chat, combat, ping, or keep-alive packets).
     *
     * @param channel The Netty channel
     */
    public void recordImmediateFlush(final Channel channel) {
        this.immediateFlushes.incrementAndGet();
        if (channel != null) {
            final AtomicBoolean ticket = this.pendingFlushChannels.remove(channel);
            if (ticket != null) {
                ticket.set(false);
            }
        }
    }

    /**
     * Flushes all pending channels at the end of the server tick.
     * Must be called at the conclusion of each tick loop before socket polling.
     *
     * @return Number of channels that were claimed and queued for flush in this pass
     */
    public int flushPendingChannels() {
        if (this.pendingFlushChannels.isEmpty()) {
            return 0;
        }

        int flushed = 0;
        for (final Map.Entry<Channel, AtomicBoolean> entry : this.pendingFlushChannels.entrySet()) {
            final Channel channel = entry.getKey();
            final AtomicBoolean ticket = entry.getValue();
            if (!this.pendingFlushChannels.remove(channel, ticket)) {
                continue; // claimed concurrently
            }
            if (!channel.isActive()) {
                // Release the ticket so a channel that comes back can register again;
                // a stuck "true" ticket would permanently suppress registration.
                ticket.set(false);
                continue;
            }
            if (channel.eventLoop().inEventLoop()) {
                this.flushClaimed(channel, ticket);
            } else {
                channel.eventLoop().execute(() -> this.flushClaimed(channel, ticket));
            }
            flushed++;
        }
        return flushed;
    }

    private void flushClaimed(final Channel channel, final AtomicBoolean ticket) {
        // Clear first: any write dispatched after this point re-registers for the next tick,
        // which is exactly what the pre-ticket implementation did as well.
        ticket.set(false);
        try {
            channel.flush();
            this.flushesExecuted.incrementAndGet();
        } catch (final Throwable t) {
            this.flushErrors.incrementAndGet();
            LOGGER.warn("Failed to flush coalesced channel {}", channel, t);
        }
    }

    /**
     * Clears all pending flush registrations (e.g. on server reload or shutdown) and releases the
     * associated tickets.
     */
    public void clear() {
        for (final Map.Entry<Channel, AtomicBoolean> entry : this.pendingFlushChannels.entrySet()) {
            final AtomicBoolean ticket = entry.getValue();
            if (this.pendingFlushChannels.remove(entry.getKey(), ticket)) {
                ticket.set(false);
            }
        }
        this.pendingFlushChannels.clear();
    }

    public void setEnabled(final boolean enabled) {
        this.enabled.set(enabled);
        if (!enabled) {
            this.clear();
        }
    }

    public boolean isEnabled() {
        return this.enabled.get();
    }

    public int pendingChannelCount() {
        return this.pendingFlushChannels.size();
    }

    public void resetMetrics() {
        this.clear();
        this.packetsCoalesced.reset();
        this.flushesExecuted.set(0);
        this.immediateFlushes.set(0);
        this.flushErrors.set(0);
    }

    public CoalesceMetrics metrics() {
        return new CoalesceMetrics(
            this.enabled.get(),
            this.packetsCoalesced.sum(),
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
