package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.List;

/**
 * AGC (Advanced Gamedev Craft) — Paper fork performance tuning constants.
 *
 * <p>이 클래스는 AGC의 모든 최적화 정책의 단일 진입점입니다.
 * 대다수 값은 <b>하드코딩</b>되어 있으며, 플러그인 호환성과 바닐라 체감을 유지하면서
 * 단일 인스턴스에서 최대한 많은 동시접속/월드/TPS 안정성을 끌어올리도록 튜닝되어 있습니다.</p>
 *
 * <h2>튜닝 목표</h2>
 * <ul>
 *   <li>TPS 20 (mspt 50) 유지</li>
 *   <li>단일 인스턴스에서 가능한 한 많은 동시접속자 처리</li>
 *   <li>수백 개 다중 월드 안정 운영</li>
 *   <li>바닐라 게임플레이/플러그인 호환성 100% 유지</li>
 * </ul>
 *
 * <p><b>참고:</b> 단일 Paper 인스턴스로 10,000 동시접속 / 500개 월드를
 * TPS 20으로 돌리는 것은 네트워크·하드웨어 한계로 사실상 불가능합니다.
 * AGC는 이 한도 안에서 <b>최대한의 헤드룸</b>을 만들기 위한 튜닝을 제공합니다.</p>
 *
 * <h2>Safety</h2>
 * <p>각 상수는 {@link Safety} 레이블로 분류되어 있다. {@link AgcCapabilityMatrix}가
 * 이 레이블을 보고 vanilla / agc-baseline / agc-aggressive 모드별로 어떤 상수를
 * 노출할지 결정한다.</p>
 */
public final class AgcPerformanceTuning {

    private AgcPerformanceTuning() {}

    /**
     * 안전 등급. {@link AgcCapabilityMatrix}가 이 값을 보고 모드별 노출 여부를 결정.
     */
    public enum Safety {
        /** vanilla / Paper 기본값과 동일. 어떤 플러그인도 영향 없음. */
        VANILLA_SAFE,
        /** vanilla 동작은 유지하되 추가 GC / CPU 오버헤드 발생 가능. 기본 활성. */
        BASELINE,
        /** AGC 적응형 정책이 활성화. 모르는 플러그인과 충돌 가능 → opt-in 권장. */
        AGGRESSIVE_BUT_SAFE,
        /** 의미적 영향 가능. 운영자 검증 후에만 사용. */
        EXPERIMENTAL
    }

    // ========================================================================
    // Network / Netty
    // ========================================================================

    /**
     * 청크 전송당 한 틱에서 보낼 수 있는 최대 청크 수.
     * 기본 8 → 16으로 올려 멀티 코어 + Netty 대역폭 활용 극대화.
     * 너무 높이면 클라이언트 처리 지연이 발생할 수 있어 16이 안전 상한.
     */
    public static final Safety MAX_CHUNKS_SENT_PER_TICK_SAFETY = Safety.BASELINE;
    public static final int MAX_CHUNKS_SENT_PER_TICK = 16;

    /**
     * Netty 채널의 auto-read 임계치 (바이트).
     * 너무 작은 패킷에서 read를 중단하지 않도록 임계치를 키움.
     */
    public static final Safety CHANNEL_WATERMARK_SAFETY = Safety.VANILLA_SAFE;
    public static final int CHANNEL_AUTO_READ_LOW_WATERMARK = 1 << 16;   // 64 KiB
    public static final int CHANNEL_AUTO_READ_HIGH_WATERMARK = 1 << 20;  // 1 MiB

    /**
     * 채널 read timeout (초). 헬스 체크 용.
     */
    public static final Safety CHANNEL_READ_TIMEOUT_SAFETY = Safety.VANILLA_SAFE;
    public static final int CHANNEL_READ_TIMEOUT_SECONDS = 30;

    /**
     * 압축된 패킷에 대해 zlib 대신 zstd를 사용할지 여부.
     * 클라이언트가 1.21+ 부터 zstd를 지원하므로 CPU vs 대역폭 트레이드오프가 좋음.
     * 보수적으로는 false, 고성능 환경에서 true.
     */
    public static final Safety USE_ZSTD_COMPRESSION_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final boolean USE_ZSTD_COMPRESSION = true;

    /**
     * 패킷 압축 임계값 (바이트). 이 크기 이하는 압축하지 않음.
     * 기본 256 → 512로 올려 압축 CPU 비용 절감.
     */
    public static final Safety COMPRESSION_THRESHOLD_SAFETY = Safety.BASELINE;
    public static final int COMPRESSION_THRESHOLD = 512;

    /**
     * 패킷 압축 레벨 (zlib 0~9, zstd -7~22).
     * zstd: 3 = 기본 (CPU/대역폭 균형)
     */
    public static final Safety COMPRESSION_LEVEL_SAFETY = Safety.BASELINE;
    public static final int COMPRESSION_LEVEL = 3;

    // ========================================================================
    // Chunk / World
    // ========================================================================

    /**
     * 기본 서버 view-distance.
     * 플러그인이 덮어쓸 수 있는 기본값만 위로 끌어올림.
     */
    public static final Safety DEFAULT_VIEW_DISTANCE_SAFETY = Safety.VANILLA_SAFE;
    public static final int DEFAULT_SERVER_VIEW_DISTANCE = 12;
    public static final int DEFAULT_SERVER_SIMULATION_DISTANCE = 8;

    /**
     * 청크 한 번에 로드 시도 (청크 send per tick과 짝).
     */
    public static final Safety CHUNK_LOADS_PER_TICK_SAFETY = Safety.BASELINE;
    public static final int CHUNK_LOADS_PER_TICK = 16;

    /**
     * 청크 언로드 큐 drain 한도.
     * 메인 스레드 블로킹 방지를 위해 batched drain.
     */
    public static final Safety UNLOAD_QUEUE_DRAIN_PER_TICK_SAFETY = Safety.BASELINE;
    public static final int UNLOAD_QUEUE_DRAIN_PER_TICK = 64;

    /**
     * Eager save 대상 청크 한 틱당 처리량.
     */
    public static final Safety EAGER_SAVE_CHUNKS_PER_TICK_SAFETY = Safety.BASELINE;
    public static final int EAGER_SAVE_CHUNKS_PER_TICK = 32;

    // ========================================================================
    // Chunk packet cache
    // ========================================================================

    /**
     * 청크 packet 캐시 (이미 직렬화된 패킷을 청크 dirty 전까지 재사용).
     */
    public static final Safety CHUNK_PACKET_CACHE_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final boolean CACHE_CHUNK_PACKETS = true;

    /**
     * 청크 packet 캐시 최대 엔트리 수 (메모리 cap).
     * 한 청크당 평균 ~64 KiB, 1024 엔트리 = ~64 MiB.
     */
    public static final int CHUNK_PACKET_CACHE_MAX_ENTRIES = 1024;

    /**
     * 청크 packet 캐시 최대 총 바이트 (hard cap).
     * 기본 64 MiB.
     */
    public static final long CHUNK_PACKET_CACHE_MAX_BYTES = 64L * 1024L * 1024L;

    /**
     * Eviction 1회당 sample 크기. sample 중 가장 오래된 일부만 제거.
     */
    public static final int CHUNK_PACKET_CACHE_EVICT_SAMPLE_SIZE = 16;

    /**
     * Eviction 배치 비율 (numerator). sample 중 numerator/denom 만큼 제거.
     * 기본 1/4.
     */
    public static final int CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_NUMERATOR = 1;
    public static final int CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_DENOM = 4;

    // ========================================================================
    // Entity
    // ========================================================================

    /**
     * 엔티티 트래킹 업데이트 주기 (틱). 기본 1.
     * 1 = 매 틱 전송 (vanilla), 2 = 30Hz, 3 = 20Hz...
     * 원거리 엔티티에 한해 2~3으로 분산시켜 네트워크 부하 분산.
     */
    public static final Safety ENTITY_TRACKING_INTERVAL_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final int ENTITY_TRACKING_BASE_INTERVAL = 1;
    public static final int ENTITY_TRACKING_DISTANT_INTERVAL = 2;

    /**
     * 플레이어별 가시 엔티티 수 soft cap.
     * 이 이상은 거리/시야 기반 priority 큐로 처리.
     */
    public static final int ENTITY_VIEW_SOFT_CAP = 200;

    /**
     * PathFinding batch 크기 (Mob tick을 chunk region 안에서 batched 처리).
     */
    public static final int PATHFINDING_BATCH_SIZE = 16;

    // ========================================================================
    // Multi-world (500+ targets)
    // ========================================================================

    /**
     * 비활성 월드 unload 지연 시간 (틱). 20 = 1초.
     * 500개 월드 운영 시 메모리 압박이 크므로 적극적으로 unload.
     */
    public static final Safety INACTIVE_WORLD_UNLOAD_DELAY_SAFETY = Safety.BASELINE;
    public static final int INACTIVE_WORLD_UNLOAD_DELAY_TICKS = 20 * 30;  // 30초

    /**
     * 월드별 chunk region 분할 크기 (청크 단위).
     * Folia regionized 스케줄러의 기본 region 크기.
     */
    public static final int WORLD_REGION_CHUNK_SIZE = 8;

    /**
     * 글로벌 region tick 스레드 풀 크기.
     * CPU 코어 수와 동일하게 맞추는 것이 일반적.
     * AGC는 환경에서 감지한 코어 수의 75%를 사용 (다른 워크로드와 코어 공유 고려).
     */
    public static final double GLOBAL_REGION_THREAD_POOL_RATIO = 0.75;

    // ========================================================================
    // GC / Memory
    // ========================================================================

    /**
     * ChunkHolder / Entity / PacketBuffer 등 hot object pool 사용.
     */
    public static final Safety USE_HOT_OBJECT_POOLS_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final boolean USE_HOT_OBJECT_POOLS = true;

    // ========================================================================
    // Anti-XRay
    // ========================================================================

    /**
     * Anti-XRay는 CPU 비용이 크므로 기본 off. 서버 운영자가 필요 시 활성화.
     */
    public static final boolean ANTIXRAY_DEFAULT_ENABLED = false;

    // ========================================================================
    // Hard-coded batching thresholds
    // ========================================================================

    /**
     * Player packet 처리 batch 크기.
     * 한 Netty read에서 최대 이만큼의 패킷을 한 번에 처리.
     */
    public static final int PLAYER_PACKET_BATCH_SIZE = 32;

    /**
     * Block update broadcast per-tick cap.
     * 한 위치에서 한 틱에 broadcast 가능한 최대 인접 플레이어 수.
     */
    public static final int BLOCK_UPDATE_BROADCAST_PER_TICK = 256;

    // ========================================================================
    // Priority-based packet budget
    // ========================================================================

    /**
     * 우선순위 기반 패킷 budget 사용.
     * 높은 우선순위(movement, combat)는 보존, 낮은 우선순위(chunk light, cosmetic)는
     * 지연 가능.
     */
    public static final Safety PACKET_PRIORITY_BUDGET_SAFETY = Safety.AGGRESSIVE_BUT_SAFE;
    public static final boolean USE_PACKET_PRIORITY_BUDGET = true;

    /**
     * 우선순위 budget. ms 단위 deadline. 이 시간 안에 flush하지 못한 패킷은
     * 다음 tick으로 밀림.
     */
    public static final long PACKET_PRIORITY_DEADLINE_MS = 50L;

    /**
     * 우선순위별 기본 budget (bytes per tick).
     */
    public static final int PACKET_PRIORITY_HIGH_BUDGET = 256 * 1024;    // 256 KiB
    public static final int PACKET_PRIORITY_NORMAL_BUDGET = 128 * 1024;  // 128 KiB
    public static final int PACKET_PRIORITY_LOW_BUDGET = 64 * 1024;     // 64 KiB

    // ========================================================================
    // World Hibernation
    // ========================================================================

    /**
     * 유휴 월드가 동면 상태로 진입하기 전 대기하는 유예 틱(Grace Period).
     */
    public static final Safety WORLD_HIBERNATION_SAFETY = Safety.BASELINE;
    public static final int WORLD_HIBERNATION_GRACE_PERIOD_TICKS = 100;

    // ========================================================================
    // Validation
    // ========================================================================

    /**
     * 모든 상수가 sane한지 검증. 빌드/시작 시 호출.
     *
     * @return 검증 실패 시 문제 설명 리스트 (빈 리스트면 정상).
     */
    public static List<String> validate() {
        final List<String> issues = new ArrayList<>();
        if (MAX_CHUNKS_SENT_PER_TICK <= 0) {
            issues.add("MAX_CHUNKS_SENT_PER_TICK must be > 0");
        }
        if (CHANNEL_AUTO_READ_LOW_WATERMARK <= 0 || CHANNEL_AUTO_READ_HIGH_WATERMARK <= 0) {
            issues.add("Channel watermarks must be > 0");
        }
        if (CHANNEL_AUTO_READ_LOW_WATERMARK > CHANNEL_AUTO_READ_HIGH_WATERMARK) {
            issues.add("CHANNEL_AUTO_READ_LOW_WATERMARK must be <= HIGH");
        }
        if (CHANNEL_READ_TIMEOUT_SECONDS <= 0) {
            issues.add("CHANNEL_READ_TIMEOUT_SECONDS must be > 0");
        }
        if (COMPRESSION_THRESHOLD < 0) {
            issues.add("COMPRESSION_THRESHOLD must be >= 0");
        }
        if (CHUNK_PACKET_CACHE_MAX_ENTRIES < 0) {
            issues.add("CHUNK_PACKET_CACHE_MAX_ENTRIES must be >= 0");
        }
        if (CHUNK_PACKET_CACHE_MAX_BYTES < 0) {
            issues.add("CHUNK_PACKET_CACHE_MAX_BYTES must be >= 0");
        }
        if (CHUNK_PACKET_CACHE_EVICT_SAMPLE_SIZE <= 0) {
            issues.add("CHUNK_PACKET_CACHE_EVICT_SAMPLE_SIZE must be > 0");
        }
        if (CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_NUMERATOR <= 0
            || CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_DENOM <= 0
            || CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_NUMERATOR > CHUNK_PACKET_CACHE_EVICT_BATCH_RATIO_DENOM) {
            issues.add("Eviction batch ratio must be in (0, 1]");
        }
        if (GLOBAL_REGION_THREAD_POOL_RATIO <= 0.0 || GLOBAL_REGION_THREAD_POOL_RATIO > 1.0) {
            issues.add("GLOBAL_REGION_THREAD_POOL_RATIO must be in (0, 1]");
        }
        if (DEFAULT_SERVER_VIEW_DISTANCE < 2) {
            issues.add("DEFAULT_SERVER_VIEW_DISTANCE must be >= 2");
        }
        if (DEFAULT_SERVER_SIMULATION_DISTANCE < 2) {
            issues.add("DEFAULT_SERVER_SIMULATION_DISTANCE must be >= 2");
        }
        if (PACKET_PRIORITY_DEADLINE_MS < 1L || PACKET_PRIORITY_DEADLINE_MS > 1000L) {
            issues.add("PACKET_PRIORITY_DEADLINE_MS out of range [1, 1000]");
        }
        return issues;
    }
}
