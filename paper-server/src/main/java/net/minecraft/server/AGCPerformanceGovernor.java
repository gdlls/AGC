package net.minecraft.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime rollback governor for AGC experimental features.
 * <p>
 * It records coarse health signals and can deny opt-in features for the rest of
 * the process if they appear to be causing runaway MSPT, plugin exceptions or
 * queue backlog. Defaults are intentionally conservative and do not change
 * vanilla/Paper behaviour unless an AGC integration consults the governor.
 * Alpha8 callers prefer semantic budget tightening before disabling broad features.
 */
public final class AGCPerformanceGovernor {
    public static final AGCPerformanceGovernor INSTANCE = new AGCPerformanceGovernor();

    public enum Feature {
        PARALLEL_WORLD_TICK,
        PACKET_BUDGETING,
        CHUNK_QUEUE_BUDGETING,
        HIT_REWIND,
        SPATIAL_GRID_FAST_PATH,
        ASYNC_CHUNK_SNAPSHOT_STAGING
    }

    public enum Decision {
        ALLOW,
        THROTTLE,
        DISABLE_UNTIL_RESTART
    }

    private final EnumSet<Feature> disabledUntilRestart = EnumSet.noneOf(Feature.class);
    private final EnumMap<Feature, AtomicLong> rollbackCounts = new EnumMap<>(Feature.class);
    private final EnumMap<Feature, String> disabledReasons = new EnumMap<>(Feature.class);
    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong pluginExceptionSamples = new AtomicLong();
    private volatile double lastMspt;
    private volatile double peakMspt;
    private volatile int lastChunkQueueDepth;
    private volatile int lastPacketQueueDepth;
    private volatile boolean automaticRollbackEnabled = true;
    private volatile double msptDisableThreshold = 80.0D;
    private volatile int queueDisableThreshold = 50_000;
    private volatile int pluginExceptionDisableThreshold = 3;

    private AGCPerformanceGovernor() {
        for (final Feature feature : Feature.values()) {
            this.rollbackCounts.put(feature, new AtomicLong());
        }
    }

    public boolean isAutomaticRollbackEnabled() {
        return this.automaticRollbackEnabled;
    }

    public void setAutomaticRollbackEnabled(final boolean automaticRollbackEnabled) {
        this.automaticRollbackEnabled = automaticRollbackEnabled;
    }

    public synchronized void configure(final double msptDisableThreshold, final int queueDisableThreshold, final int pluginExceptionDisableThreshold) {
        this.msptDisableThreshold = Math.max(50.0D, msptDisableThreshold);
        this.queueDisableThreshold = Math.max(1, queueDisableThreshold);
        this.pluginExceptionDisableThreshold = Math.max(1, pluginExceptionDisableThreshold);
    }

    public synchronized Decision recordHealthSample(
        final double mspt,
        final int pluginExceptionsThisTick,
        final int chunkQueueDepth,
        final int packetQueueDepth
    ) {
        this.samples.incrementAndGet();
        this.lastMspt = Math.max(0.0D, mspt);
        this.peakMspt = Math.max(this.peakMspt, this.lastMspt);
        this.lastChunkQueueDepth = Math.max(0, chunkQueueDepth);
        this.lastPacketQueueDepth = Math.max(0, packetQueueDepth);
        if (pluginExceptionsThisTick > 0) {
            this.pluginExceptionSamples.addAndGet(pluginExceptionsThisTick);
        }
        if (!this.automaticRollbackEnabled) {
            return Decision.ALLOW;
        }
        if (this.lastMspt >= this.msptDisableThreshold
            || this.lastChunkQueueDepth >= this.queueDisableThreshold
            || this.lastPacketQueueDepth >= this.queueDisableThreshold
            || pluginExceptionsThisTick >= this.pluginExceptionDisableThreshold) {
            return Decision.THROTTLE;
        }
        return Decision.ALLOW;
    }

    public synchronized void disableFeatureUntilRestart(final Feature feature, final String reason) {
        if (feature == null) {
            return;
        }
        this.disabledReasons.put(feature, reason == null || reason.isBlank() ? "unspecified" : reason);
        if (this.disabledUntilRestart.add(feature)) {
            this.rollbackCounts.get(feature).incrementAndGet();
            AGCStabilityJournal.INSTANCE.record("rollback", "disabled " + feature + " until restart: " + this.disabledReasons.get(feature));
        }
    }

    public synchronized boolean isFeatureAllowed(final Feature feature) {
        return feature != null && !this.disabledUntilRestart.contains(feature);
    }

    public synchronized Set<Feature> disabledFeaturesSnapshot() {
        if (this.disabledUntilRestart.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(this.disabledUntilRestart));
    }

    public synchronized void resetManualForTestingOnly() {
        this.disabledUntilRestart.clear();
        this.disabledReasons.clear();
        this.samples.set(0L);
        this.pluginExceptionSamples.set(0L);
        this.lastMspt = 0.0D;
        this.peakMspt = 0.0D;
        this.lastChunkQueueDepth = 0;
        this.lastPacketQueueDepth = 0;
        AGCStabilityJournal.INSTANCE.record("rollback", "manual rollback state reset");
    }

    public Map<Feature, Long> rollbackCountsSnapshot() {
        final EnumMap<Feature, Long> copy = new EnumMap<>(Feature.class);
        for (final Map.Entry<Feature, AtomicLong> entry : this.rollbackCounts.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(copy);
    }

    public synchronized Map<Feature, String> disabledReasonsSnapshot() {
        if (this.disabledReasons.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new EnumMap<>(this.disabledReasons));
    }

    public String statusLine() {
        return "AGCPerformanceGovernor{samples=" + this.samples.get()
            + ", lastMspt=" + this.lastMspt
            + ", peakMspt=" + this.peakMspt
            + ", chunkQueue=" + this.lastChunkQueueDepth
            + ", packetQueue=" + this.lastPacketQueueDepth
            + ", pluginExceptionSamples=" + this.pluginExceptionSamples.get()
            + ", disabled=" + this.disabledFeaturesSnapshot()
            + ", reasons=" + this.disabledReasonsSnapshot()
            + '}';
    }
}
