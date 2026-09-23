package io.papermc.paper.agc;

import io.papermc.paper.configuration.GlobalConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AGC — Applies the {@code config/agc.yml} settings (surfaced as
 * {@link GlobalConfiguration.Agc}) to the {@link AgcCapabilityMatrix}.
 *
 * <p>agc.yml is authoritative for performance controls: each sync
 * clears matrix runtime overrides first, then re-applies the file's decisions.
 * Config booleans enable or disable features; dormant features
 * (no production consumer) stay off. Runs at global-config load — before
 * networking and worlds start — and on every reload.</p>
 */
public final class AgcConfigSync {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcConfigSync.class);
    private static final AgcConfigSync INSTANCE = new AgcConfigSync();

    private final AtomicBoolean bootstrapSynced = new AtomicBoolean(false);
    private final java.util.Set<AgcCapabilityMatrix.Feature> configAppliedFeatures =
        java.util.Collections.synchronizedSet(java.util.EnumSet.noneOf(AgcCapabilityMatrix.Feature.class));

    public void notifyOperatorOverride(final AgcCapabilityMatrix.Feature feature) {
        this.configAppliedFeatures.remove(feature);
    }

    public static AgcConfigSync get() {
        return INSTANCE;
    }

    private AgcConfigSync() {}

    /**
     * One-shot bootstrap sync. Safe to call early (before config load) — it will
     * simply defer until the configuration instance exists.
     *
     * @return {@code true} if the sync ran (config was available)
     */
    public boolean syncIfNeeded() {
        if (this.bootstrapSynced.get()) {
            return true;
        }
        if (!this.sync(false)) {
            return false;
        }
        this.bootstrapSynced.set(true);
        return true;
    }

    /**
     * Applies file config to the capability matrix.
     */
    public synchronized void syncLoadedConfiguration() {
        final GlobalConfiguration config = GlobalConfiguration.get();
        if (config == null || config.agc == null) {
            throw new IllegalStateException("AGC configuration has not been loaded");
        }
        this.syncLoadedConfiguration(config.agc);
    }

    synchronized void syncLoadedConfiguration(final GlobalConfiguration.Agc agc) {
        AgcCapabilityMatrix.clearRuntimeOverrides();
        this.configAppliedFeatures.clear();
        this.sync(agc, false);
        this.bootstrapSynced.set(true);
    }

    public synchronized boolean sync(final boolean respectExistingOverrides) {
        final GlobalConfiguration config;
        try {
            config = GlobalConfiguration.get();
        } catch (final Throwable t) {
            return false;
        }
        if (config == null || config.agc == null) {
            return false;
        }

        return this.sync(config.agc, respectExistingOverrides);
    }

    synchronized boolean sync(final GlobalConfiguration.Agc agc, final boolean respectExistingOverrides) {
        // mode is authoritative: vanilla is a real Paper-path control arm, not a decorative key.
        if (AgcModePolicy.isVanilla(agc.mode)) {
            this.configAppliedFeatures.clear();
            AgcModePolicy.forceVanillaPath(agc);
            return true;
        }

        final Map<AgcCapabilityMatrix.Feature, Boolean> KEYS = new EnumMap<>(AgcCapabilityMatrix.Feature.class);
        final boolean master = agc.performance.maxOptimizationBatch;
        KEYS.put(AgcCapabilityMatrix.Feature.JIGSAW_BOX_OCTREE, master && agc.performance.jigsawBoxOctree);
        KEYS.put(AgcCapabilityMatrix.Feature.TEMPLATE_POOL_DEDUP, master && agc.performance.templatePoolDedup);
        KEYS.put(AgcCapabilityMatrix.Feature.FAST_NOISE_ENGINE, master && agc.performance.fastNoiseEngine);
        KEYS.put(AgcCapabilityMatrix.Feature.FAST_NOISE_GENERATOR, master && agc.performance.fastNoiseEngine);
        KEYS.put(AgcCapabilityMatrix.Feature.LITHIUM_COLLISION_ENGINE, master && agc.performance.lithiumCollisionEngine);
        KEYS.put(AgcCapabilityMatrix.Feature.POI_SEARCH_ENGINE, master && agc.performance.poiSearchEngine);
        KEYS.put(AgcCapabilityMatrix.Feature.STRUCTURE_NBT_PRUNER, master && agc.performance.structureNbtPruner);
        KEYS.put(AgcCapabilityMatrix.Feature.PARALLEL_LIGHT_ENGINE, master && agc.performance.parallelLightEngine);
        KEYS.put(AgcCapabilityMatrix.Feature.SPAWNER_DENSITY_OPTIMIZER, master && agc.performance.spawnerDensityOptimizer);
        // Behaviour-affecting features that used to default on just because they were not listed here:
        // the hopper target-container cache, the villager AI/golem-gossip rate limiter and the AI
        // batch split. They are now config-authoritative and default off (see agc.yml comments).
        KEYS.put(AgcCapabilityMatrix.Feature.HOPPER_OPTIMIZER, master && agc.performance.hopperOptimizerCache);
        KEYS.put(AgcCapabilityMatrix.Feature.VILLAGER_AI_OPTIMIZER, master && agc.performance.villagerAiOptimizer);
        // Registry-sync encoding replay is byte-identical to the uncached encode (the payload is the
        // same shared packet instance for every recipient and it never reaches an outbound plugin
        // handler in a different form), so it rides the max-optimization batch with no extra config key.
        KEYS.put(AgcCapabilityMatrix.Feature.REGISTRY_ENCODING_CACHE, master);
        // The tracker idle skip and the activation iterate-once sweep change *when* AGC re-evaluates
        // tracking/activation, so they must obey the mode gate and be switchable at runtime for paired
        // A/B runs. The config booleans below are their file-level on/off switches.
        KEYS.put(AgcCapabilityMatrix.Feature.ENTITY_TRACKER_IDLE_SKIP, master && agc.performance.trackerIdleSkipFastPath);
        KEYS.put(AgcCapabilityMatrix.Feature.ACTIVATION_RANGE_ITERATE_ONCE, master && agc.performance.activationRangeIterateOnceFastPath);
        KEYS.put(AgcCapabilityMatrix.Feature.C2ME_CHUNK_PIPELINE, master && agc.performance.c2meChunkPipeline);
        KEYS.put(AgcCapabilityMatrix.Feature.CHUNK_PACKET_CACHE, master && agc.performance.chunkSendSerializationCache);
        KEYS.put(AgcCapabilityMatrix.Feature.CHUNK_SEND_BUDGET, master && agc.performance.chunkQueueBudgeting);
        KEYS.put(AgcCapabilityMatrix.Feature.CHUNK_UNLOAD_DRAIN, master);
        KEYS.put(AgcCapabilityMatrix.Feature.NETWORK_CHANNEL_WATERMARK, master);
        KEYS.put(AgcCapabilityMatrix.Feature.NETWORK_COMPRESSION_TUNING, master);
        // The tracker throttle visibly changes entity update cadence above 20 MSPT, so it is part
        // of the universeNetEngine opt-in (not the lossless master batch): off means Paper cadence.
        KEYS.put(AgcCapabilityMatrix.Feature.ENTITY_TRACKING_INTERVAL, master && agc.performance.universeNetEngine);
        KEYS.put(AgcCapabilityMatrix.Feature.EXPLOSION_COALESCER, master && agc.performance.explosionCoalescing);
        KEYS.put(AgcCapabilityMatrix.Feature.UNIVERSE_NET_ENGINE, master && agc.performance.universeNetEngine);
        KEYS.put(AgcCapabilityMatrix.Feature.REGION_TICK_BRIDGE, master && agc.performance.regionTickBridge);
        KEYS.put(AgcCapabilityMatrix.Feature.PARALLEL_WORLD_TICK, master && agc.performance.parallelWorldTick);
        KEYS.put(AgcCapabilityMatrix.Feature.SINGLEPLAYER_FEEL_COMBAT, agc.singleplayerFeelCombat);
        KEYS.put(AgcCapabilityMatrix.Feature.NETWORK_READ_TIMEOUT, agc.networkReadTimeout);
        KEYS.put(AgcCapabilityMatrix.Feature.MULTIWORLD_UNLOAD, agc.multiworldUnload);

        // Engine-level tuning keys (not matrix gates): the parallel world tick worker count and the
        // minimum-worlds floor. Pushed on every sync — so the floor always reflects the config file.
        // Both apply live (the pool is swapped, never fixed at bootstrap).
        AgcParallelWorldTickEngine.get().applyTuning(
            agc.performance.parallelWorldTickThreads,
            agc.performance.parallelWorldTickMinWorlds
        );

        int applied = 0;
        int preserved = 0;
        for (final Map.Entry<AgcCapabilityMatrix.Feature, Boolean> entry : KEYS.entrySet()) {
            final AgcCapabilityMatrix.Feature feat = entry.getKey();
            if (respectExistingOverrides && AgcCapabilityMatrix.getRuntimeOverride(feat) != null && !this.configAppliedFeatures.contains(feat)) {
                preserved++;
                continue;
            }
            final boolean featureAllowed = feat.safety() != AgcPerformanceTuning.Safety.EXPERIMENTAL;
            AgcCapabilityMatrix.setRuntimeOverride(feat, entry.getValue() && featureAllowed && !AgcCapabilityMatrix.isDormant(feat));
            this.configAppliedFeatures.add(feat);
            applied++;
        }
        LOGGER.info("AGC config sync: master={} applied={} preservedOperatorPins={}",
            master, applied, preserved);
        return true;
    }

    /** Test hook: resets the one-shot latch. */
    void resetForTests() {
        this.bootstrapSynced.set(false);
        this.configAppliedFeatures.clear();
    }
}
