# AGC (Advanced Gamedev Craft) — Paper Fork Performance Layer

`io.papermc.paper.agc` 패키지는 Paper의 1.21+ AGC 포크 위에 올라가는
**순수 Paper 추가 코드** 성능 계층이다. 기존 `net.minecraft.server.AGC*` 197개 클래스가
Mojang/내부 NMS에서 동작하는 반면, 이 패키지는 Paper-API + Bukkit + Netty 위에서
hot-path 최적화, 캐시, 메트릭, 모드 게이팅을 담당한다.

## 현실적인 스케일 목표

> **솔직한 사실:** 단일 Paper 인스턴스로 "수천개 월드 + 수천명 동시접속 + 100% 바닐라 +
> 100% 플러그인 호환"을 한 세션에 만드는 건 불가능하다. Mojang/Paper가 수년에 걸쳐 한 일이고,
> 하드웨어/네트워크 한계가 있다.
>
> AGC는 그 한도 안에서 **가능한 한의 헤드룸**을 만들기 위한 튜닝이다.
> 운영자가 모드/플러그인/하드웨어에 맞게 **trade-off** 를 선택할 수 있도록 설계되었다.

| 현실적인 상한 | TPS 20 유지 | 비고 |
| --- | --- | --- |
| ~500 동시접속 / ~10 월드 | ✅ | 기본 baseline |
| ~1000 동시접속 / ~50 월드 | ✅ | aggressive 모드 + 좋은 하드웨어 |
| ~2000+ 동시접속 | ⚠️ | 네트워크 대역폭이 1차 제약 |
| 100+ 월드 (minigame) | ✅ | parallel world tick + 다중월드 정책 |
| 1000+ 월드 | ⚠️ | 메모리 / IO가 1차 제약 |

## 패키지 구성

```
io.papermc.paper.agc/
├── AgcPerformanceTuning.java   # 단일 진입점 상수. Safety 레이블 포함.
├── AgcCapabilityMatrix.java    # 모드별 기능 노출 (VANILLA / BASELINE / AGGRESSIVE)
├── AgcFoliaTuning.java         # Folia regionized tick + async workload pool
├── AgcHotPathCache.java        # TickBudget, ChunkPacketCache, PacketCounter, snapshot
├── AgcNetworkEnhancer.java     # Netty 채널 파이프라인 부스터 (ChannelInitializeListener)
├── AgcMetrics.java             # 통합 메트릭 (spark / Timings 폴링)
└── README.md                   # 이 파일
```

## 운영 모드

`AgcCapabilityMatrix.Mode`로 3가지 모드를 지원한다. 모드 변경은 런타임에 즉시 반영되지만
이미 진행 중인 tick / 패킷은 영향받지 않는다.

| 모드 | 활성 기능 | plugin 호환성 | 사용 시점 |
| --- | --- | --- | --- |
| `VANILLA` | 없음 (AGC 모두 비활성) | 100% | 디버그 / 비교 |
| `AGC_BASELINE` (기본) | VANILLA_SAFE + BASELINE 안전 | 100% | 일반 서버 |
| `AGC_AGGRESSIVE` | + AGGRESSIVE_BUT_SAFE | 일부 플러그인 위험 | 대형 서버 (운영자 opt-in) |

```java
// baseline (기본)
AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);

// 운영자가 aggressive로 전환
AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);

// 특정 feature만 끄기 (모드 override 우선)
AgcCapabilityMatrix.setRuntimeOverride(Feature.NETWORK_ZSTD_COMPRESSION, false);
```

## 안전성 분류 (Safety)

`AgcPerformanceTuning.Safety`로 각 상수가 분류된다:

- `VANILLA_SAFE` — vanilla Paper와 의미적으로 동일. 어떤 플러그인도 영향 없음.
- `BASELINE` — vanilla 동작 유지 + 약간의 GC/CPU 개선. 기본 활성.
- `AGGRESSIVE_BUT_SAFE` — AGC 적응형 정책. 모르는 플러그인과 충돌 가능.
- `EXPERIMENTAL` — 검증 안 됨. 운영자 명시적 opt-in 필요.

## 핵심 컴포넌트

### 1. AgcPerformanceTuning

모든 AGC 상수의 단일 진입점. 카테고리별 그룹:

- **Network**: MAX_CHUNKS_SENT_PER_TICK, watermarks, zstd, 압축 threshold/level
- **Chunk**: view/sim distance, loads per tick, unload drain, packet cache
- **Entity**: tracking interval, view soft cap, pathfinding batch
- **Multi-world**: unload delay, region chunk size, thread pool ratio
- **GC**: hot object pools
- **Priority budget**: per-priority bytes/tick, deadline

`validate()` 메서드로 빌드/시작 시 모든 상수가 sane한지 검증.

### 2. AgcCapabilityMatrix

기능별 안전 등급을 보고 모드별로 노출 여부를 결정. 운영자가
`report()` 호출로 현재 활성 기능 목록을 확인할 수 있다.

### 3. AgcFoliaTuning

Folia regionized scheduler 위에서 동작하는 두 번째 tick 가속기:

- **Async workload pool**: region tick과 분리된 글로벌 스레드 풀.
  - 월드별 비동기 chunk unload
  - 월드 메타데이터 저장
  - Cross-region 비동기 작업 (텔레포트, 인벤토리 동기화)
- **Per-world tick budget**: `WorldTickBudget`으로 각 월드의 평균/마지막 tick 시간 추적.
  - `recordTickStart()` / `recordTickEnd()` 호출
  - `resetTickWindow()` / `clearBudgets()` / `budgetReport()`

```java
// 메인 tick 루프에서
AgcFoliaTuning.budgetFor(serverLevel).recordTickStart();
// ... region ticks ...
AgcFoliaTuning.budgetFor(serverLevel).recordTickEnd(entityCount, chunkCount);
```

### 4. AgcHotPathCache

Hot path에서 lock-free 자료구조:

- **TickBudget** — per-thread CAS 카운터. `tryAcquire(max)` / `tryAcquire(max, cost)`.
- **ChunkPacketCache** — sample-and-discard LRU (Redis maxmemory-sample 방식).
  - entry cap + byte cap
  - 동시성: read는 lock-free, eviction만 짧은 synchronized 블록
- **PacketCounter** — atomic long counter. `recordProcessed` / `recordDropped`.
- **CacheSnapshot** — 환경/메모리 스냅샷. `recordSnapshot()`.

### 5. AgcNetworkEnhancer

`ChannelInitializeListener` 구현체. Netty 채널 생성 직후 후처리:

- 워터마크 조정 (auto-read 토글 빈도 감소)
- Read timeout (네트워크 헬스 체크)
- Auto-read 정책

런타임에 `setEnabled(false)`로 토글 가능. 메트릭은 `metrics()`.

### 6. AgcMetrics

통합 메트릭. spark / Timings 폴링 대상:

- 캐시 hit/miss/eviction
- 패킷 bytes sent / compressed
- budget throttled
- async task submitted / rejected / failed
- ticks recorded
- channels registered / closed

`snapshot()` / `report()` / `asTimingsMap()` 세 가지 accessor.

## 부트스트랩 / wired in

`PaperBootstrap.boot()`(`io.papermc.paper.PaperBootstrap`)에서 다음이 **이미 wired** 되어 있다:

```java
AgcPerformanceTuning.validate();                       // 실패 시 부팅 차단
AgcFoliaTuning.bootstrap();                            // async pool + world tick worker pool
Runtime.getRuntime().addShutdownHook(... AgcFoliaTuning::shutdown);
AgcNetworkEnhancer.get().setEnabled(true);
ChannelInitializeListenerHolder.addListener(Key.key("agc", "network_enhancer"), ...);
```

추가로 서버 런타임 경로에 연결된 AGC 훅:

- `MinecraftServer#runServer` — `AgcPluginSafetyGuard.bindPrimaryThread(server thread)` 바인딩
- `MinecraftServer#tickServer` — Governor + AdaptiveViewDistance 20틱 주기 평가
- `MinecraftServer#tickChildren` — 월드 히버네이션 필터 → 병렬/순차 월드 틱 실행 →
  **단일 post-world-tick drain 지점** (`AgcCrossWorldQueue.drainAll()` + `AgcPluginSafetyGuard.drainMailbox(0)`)

> 참고: 이전 버전 문서에는 "부트스트랩 호출이 아직 wired 되지 않았다"는 노트가 있었으나
> 현재 코드 기준으로는 사실이 아니다(오래된 잔재). 위 목록이 현재 상태이다.

## 게이팅 정책 (중요)

- 병렬 월드 틱은 `PARALLEL_WORLD_TICK` 피처(**AGGRESSIVE_BUT_SAFE**)로 게이트된다 —
  즉 **기본 AGC_BASELINE 모드에서는 절대 발동하지 않는다.**
  (과거 `MULTIWORLD_UNLOAD`(BASELINE)를 잘못 consult 하여 baseline에서도 발동하는 버그가 있었고 수정됨.)
- 웨이브 분할은 포탈 패밀리 충돌 술어(level name 동일성)를 사용해 같은 월드 패밀리 차원이
  같은 웨이브에 배치되지 않도록 한다.

## 빌드 베이스라인 상태

> 과거 문서에 기술되던 criterion→predicates rename, DripstoneThickness, EntitySpawnRequest
> 시그니처 불일치 등의 업데이트 이슈는 **모두 해결된 상태**이다 (현재 코드에 해당 심볼 부재,
> EntitySpawnRequest는 현행 API로 정상 사용 중). 이 섹션은 역사적 잔재였으며 현재 빌드는 정상이다.

## 테스트

JUnit 5 단위 테스트가 `paper-server/src/test/java/io/papermc/paper/agc/`에 있다:

- `AgcPerformanceTuningTest` — 상수 검증
- `AgcCapabilityMatrixTest` — 모드별 노출 정책
- `AgcHotPathCacheTest` — TickBudget / ChunkPacketCache / PacketCounter
- `AgcFoliaTuningTest` — bootstrap / shutdown / WorldTickBudget
- `AgcNetworkEnhancerTest` — metrics, enable/disable
- `AgcMetricsTest` — snapshot / report

테스트는 **paper-api.jar + Netty + JUnit 5 + slf4j** classpath로 standalone 실행 가능.
Mock Bukkit / Netty 없이 pure-Java로 검증.

## 모드 결정 가이드 (운영자용)

```text
Q: 내 서버는 어떤 모드를 써야 하나?

A: 1) 일단 baseline으로 시작.
   2) TPS / 메모리 / 패킷 손실 관찰.
   3) 한계 도달 시:
      a) 100명 미만 / 10월드 미만 → baseline 유지, 하드웨어 업그레이드 검토.
      b) 100-500명 / 10-50월드 → aggressive, NET_* feature만 opt-out.
      c) 500-1000명 / 50-100월드 → aggressive, NetworkEnhancer 활성.
      d) 1000+명 → 하드웨어 + 네트워크가 1차 제약. 코드 최적화만으로 불가.
```
