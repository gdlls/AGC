package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * AGC — 운영 모드별 기능 노출 매트릭스.
 *
 * <p>AGC는 세 가지 운영 모드를 정의한다:</p>
 * <ul>
 *   <li>{@link Mode#VANILLA}: vanilla Paper와 100% 동일. AGC 정책 일체 비활성.</li>
 *   <li>{@link Mode#AGC_BASELINE}: vanilla 호환되는 빠른 경로만 활성. 기본 모드.</li>
 *   <li>{@link Mode#AGC_AGGRESSIVE}: AGC 적응형 / 멀티스레딩 / 우선순위 budget 모두 활성.
 *       일부 플러그인과 충돌 가능 → opt-in.</li>
 * </ul>
 *
 * <p>각 기능은 {@link AgcPerformanceTuning.Safety} 레이블로 분류되어 있다.
 * 모드가 {@code VANILLA_SAFE} / {@code BASELINE} 기능은 baseline에서 활성,
 * {@code AGGRESSIVE_BUT_SAFE}는 aggressive에서만 활성,
 * {@code EXPERIMENTAL}은 운영자 명시적 활성화 필요.</p>
 *
 * <p>런타임에 {@link #setMode(Mode)}로 모드 전환 가능. 변경 즉시 적용되지만
 * 이미 진행 중인 tick / 패킷에는 영향 없음.</p>
 */
public final class AgcCapabilityMatrix {

    private AgcCapabilityMatrix() {}

    /**
     * 운영 모드.
     */
    public enum Mode {
        /** vanilla Paper와 100% 동일. */
        VANILLA,
        /** vanilla 호환 빠른 경로만 활성. 기본값. */
        AGC_BASELINE,
        /** AGC 적응형 / 멀티스레딩 / 우선순위 budget 모두 활성. */
        AGC_AGGRESSIVE
    }

    /**
     * AGC가 노출하는 기능. 각 feature는 {@link AgcPerformanceTuning.Safety} 레이블을
     * 가지고 있고 모드에 따라 활성 여부가 결정된다.
     */
    public enum Feature {
        // Network
        NETWORK_CHANNEL_WATERMARK("Netty channel write buffer watermark tuning", AgcPerformanceTuning.CHANNEL_WATERMARK_SAFETY),
        NETWORK_READ_TIMEOUT("Netty read timeout (health check)", AgcPerformanceTuning.CHANNEL_READ_TIMEOUT_SAFETY),
        NETWORK_ZSTD_COMPRESSION("zstd packet compression (1.21+ clients)", AgcPerformanceTuning.USE_ZSTD_COMPRESSION_SAFETY),
        NETWORK_COMPRESSION_TUNING("compression threshold / level", AgcPerformanceTuning.COMPRESSION_THRESHOLD_SAFETY),
        NETWORK_PACKET_PRIORITY("priority-based packet budget", AgcPerformanceTuning.PACKET_PRIORITY_BUDGET_SAFETY),

        // Chunk
        CHUNK_SEND_BUDGET("per-tick chunk send budget", AgcPerformanceTuning.MAX_CHUNKS_SENT_PER_TICK_SAFETY),
        CHUNK_LOAD_BUDGET("per-tick chunk load budget", AgcPerformanceTuning.CHUNK_LOADS_PER_TICK_SAFETY),
        CHUNK_UNLOAD_DRAIN("batched chunk unload queue drain", AgcPerformanceTuning.UNLOAD_QUEUE_DRAIN_PER_TICK_SAFETY),
        CHUNK_PACKET_CACHE("serialized chunk packet cache", AgcPerformanceTuning.CHUNK_PACKET_CACHE_SAFETY),
        DEFAULT_VIEW_DISTANCE("default view / simulation distance", AgcPerformanceTuning.DEFAULT_VIEW_DISTANCE_SAFETY),

        // Entity
        ENTITY_TRACKING_INTERVAL("entity tracking update interval", AgcPerformanceTuning.ENTITY_TRACKING_INTERVAL_SAFETY),

        // Multi-world (50+ active worlds)
        MULTIWORLD_UNLOAD("inactive-world unload delay", AgcPerformanceTuning.INACTIVE_WORLD_UNLOAD_DELAY_SAFETY),
        PARALLEL_WORLD_TICK("multi-core parallel world ticking with wave dispatch", AgcPerformanceTuning.Safety.AGGRESSIVE_BUT_SAFE),
        CROSS_WORLD_QUEUE("lock-free cross-world mutation and transfer queue", AgcPerformanceTuning.Safety.BASELINE),

        // GC
        HOT_OBJECT_POOLS("hot object pools (ChunkHolder / Entity / Buffer)", AgcPerformanceTuning.USE_HOT_OBJECT_POOLS_SAFETY);

        final String description;
        final AgcPerformanceTuning.Safety safety;

        Feature(final String description, final AgcPerformanceTuning.Safety safety) {
            this.description = description;
            this.safety = safety;
        }

        public String description() { return this.description; }
        public AgcPerformanceTuning.Safety safety() { return this.safety; }
    }

    private static volatile Mode CURRENT_MODE = Mode.AGC_BASELINE;
    private static final EnumMap<Feature, Boolean> RUNTIME_OVERRIDES = new EnumMap<>(Feature.class);

    /**
     * 현재 운영 모드.
     */
    public static Mode getMode() {
        return CURRENT_MODE;
    }

    /**
     * 운영 모드 변경. 런타임에 호출 가능.
     */
    public static void setMode(final Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        CURRENT_MODE = mode;
    }

    /**
     * 런타임에 특정 feature를 강제 on/off. 모드보다 우선.
     */
    public static void setRuntimeOverride(final Feature feature, final Boolean enabled) {
        if (feature == null) {
            throw new NullPointerException("feature");
        }
        if (enabled == null) {
            RUNTIME_OVERRIDES.remove(feature);
        } else {
            RUNTIME_OVERRIDES.put(feature, enabled);
        }
    }

    public static void clearRuntimeOverrides() {
        RUNTIME_OVERRIDES.clear();
    }

    /**
     * 특정 feature가 현재 모드 + 런타임 override 하에서 활성인지.
     */
    public static boolean isEnabled(final Feature feature) {
        if (feature == null) {
            return false;
        }
        final Boolean override = RUNTIME_OVERRIDES.get(feature);
        if (override != null) {
            return override;
        }
        return modeAllows(feature.safety, CURRENT_MODE);
    }

    /**
     * 현재 모드에서 활성인 모든 feature를 모드별로 그룹화해 반환.
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
     * 운영자에게 보여줄 human-readable 모드 보고.
     */
    public static String report() {
        final StringBuilder out = new StringBuilder(256);
        out.append("AGC mode: ").append(CURRENT_MODE).append('\n');
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

    /**
     * 모드별 노출 정책.
     *
     * <p>원칙:</p>
     * <ul>
     *   <li>VANILLA: 모든 기능 비활성. (모드 == VANILLA이면 isEnabled 항상 false)</li>
     *   <li>BASELINE: VANILLA_SAFE + BASELINE 활성. AGGRESSIVE_BUT_SAFE / EXPERIMENTAL 비활성.</li>
     *   <li>AGGRESSIVE: VANILLA_SAFE + BASELINE + AGGRESSIVE_BUT_SAFE 활성. EXPERIMENTAL은 여전히 비활성.</li>
     * </ul>
     */
    private static boolean modeAllows(final AgcPerformanceTuning.Safety safety, final Mode mode) {
        if (mode == null || safety == null) {
            return false;
        }
        return switch (mode) {
            case VANILLA -> false;
            case AGC_BASELINE -> safety == AgcPerformanceTuning.Safety.VANILLA_SAFE
                || safety == AgcPerformanceTuning.Safety.BASELINE;
            case AGC_AGGRESSIVE -> safety != AgcPerformanceTuning.Safety.EXPERIMENTAL;
        };
    }
}
