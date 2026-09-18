package io.papermc.paper.agc.network;

import io.netty.channel.Channel;
import io.papermc.paper.agc.AgcCapabilityMatrix;
import io.papermc.paper.agc.AgcHotPathRuntimeBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — UniverseSpigot / SteelMC / Pumpkin Port: network control plane.
 *
 * <p>High-player forks (UniverseSpigot, SteelMC) and from-scratch servers
 * (Pumpkin) all converge on the same network truths, ported here as pure,
 * testable policies (Netty application stays in {@code AgcNetworkEnhancer},
 * which consults this engine):</p>
 * <ul>
 *   <li><b>Allocator + watermark policy</b>: pooled direct buffers sized by
 *       player count; write-buffer watermarks that avoid auto-read flapping.</li>
 *   <li><b>Adaptive compression</b>: threshold/level follow MSPT — under tick
 *       pressure the CPU cost of compression is cut before gameplay degrades.</li>
 *   <li><b>Tracker throttle</b>: per-entity update intervals stretch with
 *       distance^2 and MSPT (near entities stay at 1-tick; distant ones batch).</li>
 *   <li><b>Broadcast batch planner</b>: fan-out work is grouped per tick-section
 *       so one chunk update produces one flush per connection, not N.</li>
 * </ul>
 *
 * <p>Parity: packet <i>contents and per-connection order</i> never change — only
 * flush timing, compression level and (config-gated) distant-entity cadence.</p>
 */
public final class AgcUniverseNetEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcUniverseNetEngine.class);
    private static final AgcUniverseNetEngine INSTANCE = new AgcUniverseNetEngine();

    private final AtomicLong compressionAdaptations = new AtomicLong();
    private final AtomicLong trackerThrottles = new AtomicLong();
    private final AtomicLong broadcastBatches = new AtomicLong();

    // Adaptive Network Admission & Staged Login Pacing
    private final ConcurrentLinkedQueue<Channel> pendingInboundChannels = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> stagedSpawnQueue = new ConcurrentLinkedQueue<>();

    private final AtomicInteger inboundChannelsThisTick = new AtomicInteger();
    private final AtomicInteger loginVerificationsThisTick = new AtomicInteger();
    private final AtomicInteger playerSpawnsThisTick = new AtomicInteger();

    private final AtomicLong inboundChannelsPaced = new AtomicLong();
    private final AtomicLong inboundChannelsAdmitted = new AtomicLong();
    private final AtomicLong loginsPaced = new AtomicLong();
    private final AtomicLong loginsAdmitted = new AtomicLong();
    private final AtomicLong spawnsPaced = new AtomicLong();
    private final AtomicLong spawnsImmediate = new AtomicLong();
    private final AtomicLong spawnsDrained = new AtomicLong();

    public static AgcUniverseNetEngine get() {
        return INSTANCE;
    }

    private AgcUniverseNetEngine() {}

    // Allocator / watermark policy (pure)

    public record ChannelTuning(
        int lowWatermarkBytes,
        int highWatermarkBytes,
        int allocatorArenaCount,
        int allocatorPageSize,
        boolean tcpNoDelay,
        boolean autoRead
    ) {}

    /**
     * Computes channel tuning for the current scale. Matches UniverseSpigot-style
     * guidance: pooled allocator arenas ~2x cores, watermarks widen with players
     * to stop auto-read flapping on bursty links.
     */
    public ChannelTuning channelTuning(final int cores, final int onlinePlayers) {
        final int safeCores = Math.max(2, cores);
        final int players = Math.max(0, onlinePlayers);
        final int low = players >= 1000 ? 1 << 17 : 1 << 16;   // 128KiB / 64KiB
        final int high = players >= 1000 ? 1 << 21 : 1 << 20;  // 2MiB / 1MiB
        final int arenas = Math.max(2, Math.min(16, safeCores * 2));
        final int page = players >= 2000 ? 16384 : 8192;
        return new ChannelTuning(low, high, arenas, page, true, true);
    }

    // Adaptive compression (pure)

    public record CompressionPolicy(int thresholdBytes, int level, String reason) {}

    /**
     * Adapts compression to tick pressure. Cool server: compress more (save
     * bandwidth). Hot server: compress less (save tick CPU). Never disables the
     * compressor — wire bytes stay valid for all clients.
     */
    public CompressionPolicy compressionPolicy(final double mspt, final int baseThreshold, final int baseLevel) {
        if (mspt >= 45.0) {
            this.compressionAdaptations.incrementAndGet();
            return new CompressionPolicy(Math.max(baseThreshold, 1024), Math.max(1, baseLevel - 2), "mspt>=45: shed compression CPU");
        }
        if (mspt >= 30.0) {
            this.compressionAdaptations.incrementAndGet();
            return new CompressionPolicy(Math.max(baseThreshold, 768), Math.max(1, baseLevel - 1), "mspt>=30: ease compression CPU");
        }
        return new CompressionPolicy(baseThreshold, baseLevel, "nominal");
    }

    // Tracker throttle (pure)

    /**
     * Per-entity tracking interval in ticks. Near entities (or combat-tagged ones)
     * always update every tick; distant idle entities batch up. Gated by
     * {@code ENTITY_TRACKING_INTERVAL} — VANILLA mode callers must pass
     * {@code aggressiveAllowed=false} to force 1.
     *
     * @param distanceSq squared distance to the tracking player
     * @param mspt current tick time
     * @param combatTagged entity recently damaged / attacking
     * @param aggressiveAllowed capability gate result
     */
    public int trackingInterval(
        final double distanceSq,
        final double mspt,
        final boolean combatTagged,
        final boolean aggressiveAllowed
    ) {
        if (!aggressiveAllowed || combatTagged) {
            return 1;
        }
        // Near field (<= 32 blocks): always full rate — combat feel is sacred.
        if (distanceSq <= 1024.0) {
            return 1;
        }
        // Healthy server: full rate everywhere. Throttling engages only under real
        // tick pressure, so AGGRESSIVE mode is byte-identical to Paper while cool.
        if (mspt < 20.0) {
            return 1;
        }
        this.trackerThrottles.incrementAndGet();
        if (mspt >= 40.0) {
            return distanceSq > 9216.0 ? 4 : 3; // >96 blocks: 4-tick; else 3-tick
        }
        if (mspt >= 30.0) {
            return distanceSq > 9216.0 ? 3 : 2;
        }
        return distanceSq > 16384.0 ? 2 : 1; // >128 blocks: half rate only while warm (mspt>=20 above)
    }

    public boolean isTrackingAggressionAllowed() {
        return AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.ENTITY_TRACKING_INTERVAL);
    }

    // Broadcast batch planner (pure)

    public record BroadcastBatchPlan(int batches, int flushesSaved, String reason) {}

    /**
     * Plans fan-out batches: one flush per connection per batch instead of one
     * per packet. Pure arithmetic — the actual Netty writes stay ordered.
     */
    public BroadcastBatchPlan planBroadcast(final int packets, final int recipients) {
        if (packets <= 0 || recipients <= 0) {
            return new BroadcastBatchPlan(0, 0, "empty");
        }
        // Batch width 8: amortizes flush syscalls while keeping latency < 1 tick.
        final int batches = Math.max(1, (packets + 7) / 8);
        final int naiveFlushes = packets * recipients;
        final int batchedFlushes = batches * recipients;
        this.broadcastBatches.incrementAndGet();
        return new BroadcastBatchPlan(batches, Math.max(0, naiveFlushes - batchedFlushes), "8-wide flush batches");
    }

    // Adaptive Network Admission & Staged Login Pacing

    public double currentMspt() {
        return AgcHotPathRuntimeBridge.get().getLastMspt();
    }

    public boolean isAdmissionEnabled() {
        return AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.UNIVERSE_NET_ENGINE);
    }

    public int inboundAdmissionBudget(final double mspt) {
        if (mspt >= 45.0) return 3; // Critical: preserve 45ms SLO gate
        if (mspt >= 35.0) return 8; // High pressure
        if (mspt >= 20.0) return 15; // Moderate
        return 30; // Nominal: fast admission
    }

    public int loginVerificationBudget(final double mspt) {
        // AGC - login verification (encryption/auth in Connection.tick) is off-main-thread capable
        // work gated per tick; the old budgets (2/5/10/20) queued every verification behind up to
        // 2.5s of pacing at 20 joins/tick, which users perceive as "slow login". Verification cost
        // is dominated by the authlib HTTP call, not tick CPU, so raise all tiers.
        if (mspt >= 45.0) return 8;
        if (mspt >= 35.0) return 16;
        if (mspt >= 20.0) return 32;
        return 64;
    }

    public int spawnAdmissionBudget(final double mspt) {
        // AGC - placeNewPlayer spawns are staged here so the tick barrier is not blown away by a
        // join wave; the PrepareSpawnTask already pre-loaded the spawn chunks before this budget
        // applies, so each slot is (mostly) a cheap entity commit. Raise tiers to match the raised
        // join gate; the maxElapsed deadline still caps the actual per-tick drain.
        if (mspt >= 45.0) return 6;
        if (mspt >= 35.0) return 10;
        if (mspt >= 20.0) return 16;
        return 24;
    }

    /**
     * Called on Netty pipeline initialization (via AgcNetworkEnhancer).
     *
     * @return true if channel is admitted immediately with autoRead=true,
     *         false if channel is queued for staged admission with autoRead=false.
     */
    public boolean onChannelInit(final Channel channel) {
        if (channel == null) return true;
        if (!isAdmissionEnabled()) {
            return true;
        }

        final double mspt = currentMspt();
        final int budget = inboundAdmissionBudget(mspt);

        // Immediate admission only if queue is empty and within per-tick budget
        if (this.pendingInboundChannels.isEmpty() && this.inboundChannelsThisTick.getAndIncrement() < budget) {
            this.inboundChannelsAdmitted.incrementAndGet();
            return true;
        }

        // Pacing required: hold socket read in staged queue without dropping connection
        try {
            channel.config().setAutoRead(false);
            this.pendingInboundChannels.add(channel);
            this.inboundChannelsPaced.incrementAndGet();
            channel.closeFuture().addListener(f -> this.pendingInboundChannels.remove(channel));
            return false;
        } catch (final Throwable t) {
            LOGGER.warn("Failed to stage channel admission, admitting immediately: {}", channel, t);
            return true;
        }
    }

    /**
     * Drains pending inbound channels up to the tick admission budget.
     * Invoked on the server main thread during tickConnection.
     */
    public int tickInboundAdmission(final double mspt) {
        if (net.minecraft.server.MinecraftServer.getServer() == null) {
            this.inboundChannelsThisTick.set(0);
        } else {
            ensureTickCounters();
        }
        if (this.pendingInboundChannels.isEmpty()) {
            return 0;
        }
        final int budget = inboundAdmissionBudget(mspt);
        int admitted = 0;
        while (admitted < budget) {
            final Channel ch = this.pendingInboundChannels.poll();
            if (ch == null) {
                break;
            }
            if (ch.isOpen() && ch.isActive()) {
                try {
                    ch.config().setAutoRead(true);
                    ch.read();
                    admitted++;
                    this.inboundChannelsAdmitted.incrementAndGet();
                } catch (final Throwable t) {
                    LOGGER.warn("Failed to activate auto-read for queued channel: {}", ch, t);
                }
            }
        }
        return admitted;
    }

    public int tickInboundAdmission() {
        return tickInboundAdmission(currentMspt());
    }

    private volatile int lastTickNumber = -1;

    private void ensureTickCounters() {
        if (net.minecraft.server.MinecraftServer.getServer() != null) {
            final int current = net.minecraft.server.MinecraftServer.currentTick;
            if (current != this.lastTickNumber) {
                this.lastTickNumber = current;
                this.playerSpawnsThisTick.set(0);
                this.loginVerificationsThisTick.set(0);
                this.inboundChannelsThisTick.set(0);
            }
        }
    }

    /**
     * Paces ServerLoginPacketListenerImpl verification in Connection.tick().
     */
    public boolean tryAcquireLoginSlot(final double mspt) {
        if (!isAdmissionEnabled()) {
            return true;
        }
        ensureTickCounters();
        final int budget = loginVerificationBudget(mspt);
        if (this.loginVerificationsThisTick.getAndIncrement() < budget) {
            this.loginsAdmitted.incrementAndGet();
            return true;
        }
        this.loginsPaced.incrementAndGet();
        return false;
    }

    public boolean tryAcquireLoginSlot() {
        return tryAcquireLoginSlot(currentMspt());
    }

    /**
     * Checks if a player entity spawn can proceed immediately this tick.
     */
    public boolean tryAcquireSpawnSlot(final double mspt) {
        if (!isAdmissionEnabled()) {
            return true;
        }
        ensureTickCounters();
        final double maxElapsed = (net.minecraft.server.MinecraftServer.getServer() != null) ? 8.0 : 18.0;
        if (AgcHotPathRuntimeBridge.get().getCurrentTickElapsedMs() >= maxElapsed) {
            return false;
        }
        final int budget = spawnAdmissionBudget(mspt);
        if (this.stagedSpawnQueue.isEmpty() && this.playerSpawnsThisTick.getAndIncrement() < budget) {
            this.spawnsImmediate.incrementAndGet();
            return true;
        }
        return false;
    }

    public boolean tryAcquireSpawnSlot() {
        return tryAcquireSpawnSlot(currentMspt());
    }

    public void queueStagedSpawn(final Runnable spawnAction) {
        if (spawnAction == null) return;
        this.stagedSpawnQueue.add(spawnAction);
        this.spawnsPaced.incrementAndGet();
    }

    /**
     * Drains staged player entity construction actions up to the tick spawn budget.
     * Invoked on the server main thread during tickConnection.
     */
    public int drainStagedSpawns(final double mspt) {
        if (net.minecraft.server.MinecraftServer.getServer() == null) {
            this.playerSpawnsThisTick.set(0);
            this.loginVerificationsThisTick.set(0);
        } else {
            ensureTickCounters();
        }
        if (this.stagedSpawnQueue.isEmpty()) {
            return 0;
        }
        final int budget = spawnAdmissionBudget(mspt);
        int drained = 0;
        final double maxDrainElapsed = (net.minecraft.server.MinecraftServer.getServer() != null) ? 10.0 : 20.0;
        while (this.playerSpawnsThisTick.get() < budget) {
            if (AgcHotPathRuntimeBridge.get().getCurrentTickElapsedMs() >= maxDrainElapsed) {
                break;
            }
            final Runnable action = this.stagedSpawnQueue.poll();
            if (action == null) {
                break;
            }
            this.playerSpawnsThisTick.incrementAndGet();
            try {
                action.run();
                drained++;
                this.spawnsDrained.incrementAndGet();
            } catch (final Throwable t) {
                LOGGER.error("AGC UniverseNetEngine: Error executing staged player spawn", t);
            }
        }
        return drained;
    }

    public int drainStagedSpawns() {
        return drainStagedSpawns(currentMspt());
    }

    public int pendingInboundCount() {
        return this.pendingInboundChannels.size();
    }

    public int stagedSpawnCount() {
        return this.stagedSpawnQueue.size();
    }

    public void clear() {
        this.pendingInboundChannels.clear();
        this.stagedSpawnQueue.clear();
        this.inboundChannelsThisTick.set(0);
        this.loginVerificationsThisTick.set(0);
        this.playerSpawnsThisTick.set(0);
        this.inboundChannelsPaced.set(0);
        this.inboundChannelsAdmitted.set(0);
        this.loginsPaced.set(0);
        this.loginsAdmitted.set(0);
        this.spawnsPaced.set(0);
        this.spawnsImmediate.set(0);
        this.spawnsDrained.set(0);
        clearMetrics();
    }

    public void clearMetrics() {
        this.compressionAdaptations.set(0);
        this.trackerThrottles.set(0);
        this.broadcastBatches.set(0);
        this.inboundChannelsPaced.set(0);
        this.inboundChannelsAdmitted.set(0);
        this.loginsPaced.set(0);
        this.loginsAdmitted.set(0);
        this.spawnsPaced.set(0);
        this.spawnsImmediate.set(0);
        this.spawnsDrained.set(0);
    }

    public NetMetrics metrics() {
        return new NetMetrics(
            this.compressionAdaptations.get(),
            this.trackerThrottles.get(),
            this.broadcastBatches.get(),
            this.inboundChannelsPaced.get(),
            this.inboundChannelsAdmitted.get(),
            this.loginsPaced.get(),
            this.loginsAdmitted.get(),
            this.spawnsPaced.get(),
            this.spawnsImmediate.get(),
            this.spawnsDrained.get(),
            this.pendingInboundChannels.size(),
            this.stagedSpawnQueue.size()
        );
    }

    public record NetMetrics(
        long compressionAdaptations,
        long trackerThrottles,
        long broadcastBatches,
        long inboundChannelsPaced,
        long inboundChannelsAdmitted,
        long loginsPaced,
        long loginsAdmitted,
        long spawnsPaced,
        long spawnsImmediate,
        long spawnsDrained,
        int pendingInboundChannels,
        int stagedSpawnsQueued
    ) {
        public long spawnsTotalAdmitted() {
            return this.spawnsImmediate + this.spawnsDrained;
        }
    }
}
