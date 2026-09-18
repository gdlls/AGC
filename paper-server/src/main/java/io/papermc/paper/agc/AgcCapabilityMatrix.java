package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * AGC — Feature capability matrix across operating modes.
 *
 * <p>Three operating modes are supported:</p>
 * <ul>
 *   <li>{@link Mode#VANILLA}: 100% identical to vanilla Paper. All AGC optimizations disabled.</li>
 *   <li>{@link Mode#AGC_BASELINE}: Enables safe, strictly vanilla-compatible fast paths.</li>
 *   <li>{@link Mode#AGC_AGGRESSIVE}: Default mode. Enables safe adaptive and multithreaded optimizations.</li>
 * </ul>
 */
public final class AgcCapabilityMatrix {

    /**
     * AGC — Wiring-audit dormancy list.
     *
     * <p>Each entry here was produced by {@code AgcFeatureWiringAuditTest}: a source scan over all
     * production roots ({@code src/main} + {@code src/minecraft}) with comment lines stripped found
     * <b>zero</b> consumers for the feature — no {@code isEnabled} gate, no config-key read, no tuning
     * -constant read. A dormant feature's {@code enabled} value is a claim about code that does not
     * exist, so the matrix refuses to make it: {@link #isEnabled} returns {@code false} for dormant
     * features in every mode. This structural guarantee ensures features without active production
     * consumers report false until actively wired.</p>
     *
     * <p>The list must stay in lockstep with reality:</p>
     * <ul>
     *   <li>If a consumer is wired for one of these features, the audit test FAILS until the entry is
     *       removed — dormancy must be re-proven, not carried forever.</li>
     *   <li>If production code starts reading a feature not in this list, the audit test FAILS until
     *       the feature is wired or declared dormant — no new silent lies.</li>
     * </ul>
     *
     * <p>Operator pins ({@code /agc override}) still win over dormancy, preserving the verified live
     * A/B workflow; dormancy only fixes the unconfigured default report.</p>
     */
    private static final java.util.Set<Feature> DORMANT_FEATURES = java.util.Set.of(
        // Network: zstd requires 1.21+ client handshake negotiation
        Feature.NETWORK_ZSTD_COMPRESSION,
        // Chunk: view distance is owned by Paper's own config
        Feature.DEFAULT_VIEW_DISTANCE,
        // Memory / JIT engines: internal routing
        Feature.OFFHEAP_SLAB_ALLOCATOR,
        Feature.JIT_TYPE_DISPATCHER
    );

    private AgcCapabilityMatrix() {}

    /**
     * @deprecated Mode distinctions have been eliminated. AGC operates with all optimizations active.
     */
    @Deprecated
    public enum Mode {
        AGC_AGGRESSIVE
    }

    /**
     * Features controlled by the capability matrix.
     */
    public enum Feature {
        NETWORK_CHANNEL_WATERMARK("Netty channel write buffer watermark tuning", AgcPerformanceTuning.CHANNEL_WATERMARK_SAFETY),
        NETWORK_READ_TIMEOUT("Optional Netty read timeout (explicit aggressive opt-in only)", AgcPerformanceTuning.Safety.AGGRESSIVE_BUT_SAFE),
        NETWORK_ZSTD_COMPRESSION("zstd packet compression (1.21+ clients)", AgcPerformanceTuning.USE_ZSTD_COMPRESSION_SAFETY),
        NETWORK_COMPRESSION_TUNING("compression threshold / level", AgcPerformanceTuning.COMPRESSION_THRESHOLD_SAFETY),
        NETWORK_PACKET_PRIORITY("priority-based packet budget", AgcPerformanceTuning.PACKET_PRIORITY_BUDGET_SAFETY),

        CHUNK_SEND_BUDGET("per-tick chunk send budget", AgcPerformanceTuning.MAX_CHUNKS_SENT_PER_TICK_SAFETY),
        CHUNK_LOAD_BUDGET("per-tick chunk load budget", AgcPerformanceTuning.CHUNK_LOADS_PER_TICK_SAFETY),
        CHUNK_UNLOAD_DRAIN("batched chunk unload queue drain", AgcPerformanceTuning.UNLOAD_QUEUE_DRAIN_PER_TICK_SAFETY),
        CHUNK_PACKET_CACHE("serialized chunk packet cache", AgcPerformanceTuning.CHUNK_PACKET_CACHE_SAFETY),
        REGISTRY_ENCODING_CACHE("replay cache for the shared registry-sync burst encoding", AgcPerformanceTuning.Safety.BASELINE),
        DEFAULT_VIEW_DISTANCE("default view / simulation distance", AgcPerformanceTuning.DEFAULT_VIEW_DISTANCE_SAFETY),

        ENTITY_TRACKING_INTERVAL("entity tracking update interval", AgcPerformanceTuning.ENTITY_TRACKING_INTERVAL_SAFETY),
        ENTITY_TRACKER_IDLE_SKIP("entity tracker idle skip (stationary pairs are not re-evaluated every tick)", AgcPerformanceTuning.Safety.BASELINE),

        MULTIWORLD_UNLOAD("inactive-world unload delay (explicit aggressive opt-in only)", AgcPerformanceTuning.Safety.AGGRESSIVE_BUT_SAFE),
        PARALLEL_WORLD_TICK("multi-core parallel world ticking with wave dispatch", AgcPerformanceTuning.Safety.AGGRESSIVE_BUT_SAFE),
        CROSS_WORLD_QUEUE("lock-free cross-world mutation and transfer queue", AgcPerformanceTuning.Safety.BASELINE),

        HOT_OBJECT_POOLS("hot object pools (ChunkHolder / Entity / Buffer)", AgcPerformanceTuning.USE_HOT_OBJECT_POOLS_SAFETY),

        SINGLEPLAYER_FEEL_COMBAT("singleplayer-feel hyper-responsive combat and sub-tick knockback tracker reordering", AgcPerformanceTuning.SINGLEPLAYER_FEEL_COMBAT_SAFETY),

        ACTIVATION_RANGE_ITERATE_ONCE("single-pass entity activation range sweep", AgcPerformanceTuning.Safety.BASELINE),
        LIGHT_BATCH_OPTIMIZER("batched StarLight calculations and nibble pool", AgcPerformanceTuning.Safety.BASELINE),
        CHUNK_L1_L2_CACHE("multi-tier L1/L2 chunk cache and deduplication", AgcPerformanceTuning.Safety.BASELINE),
        FAST_REDSTONE_ENGINE("topological SIMD fast redstone and signal caching", AgcPerformanceTuning.Safety.BASELINE),
        HOPPER_OPTIMIZER("hopper destination cache, double chest coalescing and dormancy", AgcPerformanceTuning.Safety.BASELINE),
        FAST_NETWORK_SERIALIZER("branchless VarInt codec and packet buffer pool", AgcPerformanceTuning.Safety.BASELINE),
        LOCKFREE_EVENT_DISPATCHER("lock-free hierarchical event dispatch pipeline", AgcPerformanceTuning.Safety.BASELINE),
        OFFHEAP_SLAB_ALLOCATOR("native off-heap direct buffer slab allocator", AgcPerformanceTuning.Safety.BASELINE),
        JIT_TYPE_DISPATCHER("JIT monomorphic type batching and loop unrolling", AgcPerformanceTuning.Safety.BASELINE),
        VILLAGER_AI_OPTIMIZER("villager workstation POI, trade price and gossip optimizer", AgcPerformanceTuning.Safety.BASELINE),
        VECTOR_MATH_ACCELERATOR("SIMD batch vector math and distance sweeps", AgcPerformanceTuning.Safety.BASELINE),
        SPAWNER_DENSITY_OPTIMIZER("mob spawn candidate fast player-exclusion boundary filtering", AgcPerformanceTuning.Safety.BASELINE),
        EXPLOSION_COALESCER("batched raycasting and explosion exposure cache", AgcPerformanceTuning.Safety.BASELINE),
        STRUCTURE_LAYOUT_OPTIMIZER("structure layout optimization and bounding containment pruning", AgcPerformanceTuning.Safety.BASELINE),
        FAST_NOISE_GENERATOR("SIMD bit-level gradient noise and doubled permutation tables", AgcPerformanceTuning.Safety.BASELINE),
        LITHIUM_CHUNK_REGISTER("Lithium multi-threaded resilient hot chunk cache and ThreadLocal fast-paths", AgcPerformanceTuning.Safety.BASELINE),
        JIGSAW_BOX_OCTREE("Structure Layout Optimizer BoxOctree jigsaw intersection culling", AgcPerformanceTuning.Safety.BASELINE),
        TEMPLATE_POOL_DEDUP("Structure Layout Optimizer duplicate-weight candidate skip", AgcPerformanceTuning.Safety.BASELINE),
        FAST_NOISE_ENGINE("FastNoise zero-allocation improved-Perlin sampler with doubled tables", AgcPerformanceTuning.Safety.BASELINE),
        LITHIUM_COLLISION_ENGINE("Lithium collisions fluid-push/suffocation/cramming/projectile fast predicates", AgcPerformanceTuning.Safety.BASELINE),
        POI_SEARCH_ENGINE("Lithium POI single-retrieval section index with portal fast path", AgcPerformanceTuning.Safety.BASELINE),
        STRUCTURE_NBT_PRUNER("Structure Layout Optimizer giant-NBT early-bounds prune", AgcPerformanceTuning.Safety.BASELINE),
        PARALLEL_LIGHT_ENGINE("ScalableLux parallel light-task splitter with StarLight coalescing bridge", AgcPerformanceTuning.Safety.BASELINE),
        C2ME_CHUNK_PIPELINE("C2ME async serialization, generation backpressure and IO autosizing", AgcPerformanceTuning.Safety.BASELINE),
        UNIVERSE_NET_ENGINE("optional adaptive networking and login admission (explicit aggressive opt-in only)", AgcPerformanceTuning.Safety.AGGRESSIVE_BUT_SAFE),
        REGION_TICK_BRIDGE("Folia-inspired read-only helper offload with primary-thread commit", AgcPerformanceTuning.Safety.AGGRESSIVE_BUT_SAFE);

        final String description;
        final AgcPerformanceTuning.Safety safety;

        Feature(final String description, final AgcPerformanceTuning.Safety safety) {
            this.description = description;
            this.safety = safety;
        }

        public String description() { return this.description; }
        public AgcPerformanceTuning.Safety safety() { return this.safety; }
    }

    /**
     * Immutable operator-pin snapshot. Mutators publish a replacement rather than mutating a shared
     * EnumMap: hot-path readers can therefore use the resolved boolean[] without a data race.
     */
    private static volatile Map<Feature, Boolean> RUNTIME_OVERRIDES = Collections.emptyMap();

    /**
     * AGC — Resolved enablement snapshot.
     *
     * <p>{@link #isEnabled(Feature)} is consulted from the very hottest paths in the
     * server: worldgen noise (`ImprovedNoise.gradDot` runs 8x per octave sample),
     * entity tracking, chunk send, redstone, hoppers, … A JFR profile of a live
     * server under load attributed <b>18.8% of all JVM execution samples</b> to
     * {@code modeAllows} + {@code isEnabled} because every call performed an
     * {@link EnumMap} lookup (not inlinable, and unsafely read from worker
     * threads while the main thread could mutate it) plus an enum switch.</p>
     *
     * <p>The enablement of a feature only changes when the operator changes the
     * mode or pins an override — i.e. essentially never. So we resolve the whole
     * matrix once into an immutable {@code boolean[]} and publish it through this
     * {@code volatile} reference. A read is then a volatile reference load plus
     * an array load, both of which the JIT can hoist out of tight loops; no
     * hashing, no method dispatch, no null checks, and no data race.</p>
     */
    private static volatile boolean[] ENABLED = buildEnabledSnapshot();

    /**
     * @deprecated Mode distinctions have been eliminated. Always returns {@link Mode#AGC_AGGRESSIVE}.
     */
    @Deprecated
    public static Mode getMode() {
        return Mode.AGC_AGGRESSIVE;
    }

    /**
     * @deprecated Mode distinctions have been eliminated. All AGC optimizations are permanently active.
     */
    @Deprecated
    public static synchronized void setMode(final Mode mode) {
        republishEnabledSnapshot();
    }

    /**
     * Pins an operator override for a feature at runtime.
     */
    public static synchronized void setRuntimeOverride(final Feature feature, final Boolean enabled) {
        if (feature == null) {
            throw new NullPointerException("feature");
        }
        // EnumMap's Map-copy constructor rejects an empty non-EnumMap source
        // ("Specified map is empty"), and RUNTIME_OVERRIDES is Collections.emptyMap()
        // whenever no overrides are pinned — i.e. the common case. Seed from the
        // feature class directly in that case.
        final EnumMap<Feature, Boolean> next = RUNTIME_OVERRIDES.isEmpty()
            ? new EnumMap<>(Feature.class)
            : new EnumMap<>(RUNTIME_OVERRIDES);
        if (enabled == null) {
            next.remove(feature);
        } else {
            next.put(feature, enabled);
        }
        RUNTIME_OVERRIDES = Collections.unmodifiableMap(next);
        republishEnabledSnapshot();
    }

    public static synchronized void clearRuntimeOverrides() {
        RUNTIME_OVERRIDES = Collections.emptyMap();
        republishEnabledSnapshot();
    }

    /**
     * Returns the operator-pinned override for a feature, or {@code null} when
     * the mode default applies. Used by {@code AgcConfigSync} to preserve
     * operator intent across config re-syncs.
     */
    public static Boolean getRuntimeOverride(final Feature feature) {
        if (feature == null) {
            return null;
        }
        return RUNTIME_OVERRIDES.get(feature);
    }

    /**
     * Checks if a feature is enabled under the active mode and runtime overrides.
     */
    public static boolean isEnabled(final Feature feature) {
        if (feature == null) {
            return false;
        }
        // All mode/override/dormancy decisions are resolved when a new immutable snapshot is
        // published. This is deliberately just a volatile reference load plus an array load: this
        // method is called from worldgen, entity tracking, chunk and redstone hot paths.
        return ENABLED[feature.ordinal()];
    }

    /**
     * Builds a fresh, fully resolved enablement snapshot. Never throws: a feature
     * whose safety label is unknown is treated as disabled (fail-closed).
     */
    private static boolean[] buildEnabledSnapshot() {
        final Feature[] features = Feature.values();
        final boolean[] snapshot = new boolean[features.length];
        final Map<Feature, Boolean> overrides = RUNTIME_OVERRIDES;
        for (int i = 0; i < features.length; i++) {
            final Feature feature = features[i];
            final Boolean override = overrides.get(feature);
            snapshot[i] = override != null
                ? override
                : defaultEnabled(feature);
        }
        return snapshot;
    }

    /** Retained for focused tests. */
    private static boolean resolveEnabled(final Feature feature) {
        final Boolean override = RUNTIME_OVERRIDES.get(feature);
        return override != null
            ? override
            : defaultEnabled(feature);
    }

    /**
     * Publishes a new snapshot atomically. Synchronised so concurrent mode switches
     * cannot interleave two half-applied matrices; readers never block (they only
     * load the volatile reference).
     */
    private static synchronized void republishEnabledSnapshot() {
        ENABLED = buildEnabledSnapshot();
    }

    /**
     * True when the feature has no production consumer (wiring audit) and therefore reports
     * disabled by default. An operator pin overrides this.
     */
    public static boolean isDormant(final Feature feature) {
        return feature != null && DORMANT_FEATURES.contains(feature);
    }

    /**
     * Returns all active features grouped by safety classification.
     */
    public static Map<AgcPerformanceTuning.Safety, List<Feature>> activeBySafety() {
        final EnumMap<AgcPerformanceTuning.Safety, List<Feature>> result =
            new EnumMap<>(AgcPerformanceTuning.Safety.class);
        for (final AgcPerformanceTuning.Safety s : AgcPerformanceTuning.Safety.values()) {
            result.put(s, new ArrayList<>());
        }
        for (final Feature f : Feature.values()) {
            if (isEnabled(f)) {
                result.get(f.safety).add(f);
            }
        }
        return result;
    }

    /**
     * Features with no production consumer (wiring audit). Unmodifiable.
     */
    public static java.util.Set<Feature> dormantFeatures() {
        return java.util.Collections.unmodifiableSet(DORMANT_FEATURES);
    }

    /**
     * Human-readable capability report.
     */
    public static String report() {
        final StringBuilder out = new StringBuilder(256);
        out.append("AGC Engine: All Optimizations Active\n");
        final Map<AgcPerformanceTuning.Safety, List<Feature>> active = activeBySafety();
        for (final AgcPerformanceTuning.Safety safety : AgcPerformanceTuning.Safety.values()) {
            final List<Feature> list = active.getOrDefault(safety, Collections.emptyList());
            if (list.isEmpty()) {
                continue;
            }
            out.append("  [").append(safety).append("] (").append(list.size()).append(")\n");
            for (final Feature f : list) {
                final String mark = RUNTIME_OVERRIDES.containsKey(f) ? " (override)" : "";
                out.append("    - ").append(f.name()).append(" : ").append(f.description).append(mark).append('\n');
            }
        }
        if (!RUNTIME_OVERRIDES.isEmpty()) {
            out.append("Runtime overrides: ").append(RUNTIME_OVERRIDES).append('\n');
        }
        return out.toString();
    }

    private static boolean defaultEnabled(final Feature feature) {
        return feature != Feature.SINGLEPLAYER_FEEL_COMBAT
            && !DORMANT_FEATURES.contains(feature)
            && feature.safety != AgcPerformanceTuning.Safety.EXPERIMENTAL;
    }

    @Deprecated
    private static boolean defaultEnabled(final Feature feature, final Mode mode) {
        return defaultEnabled(feature);
    }
}
