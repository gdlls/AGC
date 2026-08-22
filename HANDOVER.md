# AGC 인수인계 문서 (HANDOVER)

> 작성일: 2026-08-22 · 기준 커밋: `1e8d82033 Init base` + 본 세션 미커밋 변경분
> 대상: 이 프로젝트를 이어받는 개발자/AI 에이전트

---

## 1. 프로젝트 목표

**단일 서버 인스턴스에서 동시 접속 5,000명 × 활성 월드 500개를 TPS 20으로 안정 운영하면서,
Paper 기능·바닐라 패리티(행동 동등성)·플러그인 호환성을 완벽히 유지한다.**

- 하드웨어 가정: EPYC 9754 (128코어), RAM 1.5TB
- 바닐라 패리티 정책: **행동 동등성** 허용 (틱 순서 편차 OK — 단, 허용 집합 명세 필요 → Phase 5)
- 플러그인 호환 전략: **auto-detect + per-plugin shim 라우팅** (Folia처럼 호환성을 버리지 않는다)

### SLO (목표치)

| 지표 | 목표 |
|---|---|
| MSPT | p50 ≤ 25ms, **p99 ≤ 45ms**, p999 ≤ 49ms |
| 동시접속 / 활성월드 | 5,000 / 500 (단일 붐비는 월드 2,000명 견딤) |
| GC pause p99 | < 20ms @ 768GB~1TB heap |
| Egress | ≤ 8Gbps @ 5,000명 |
| 호환성 | 대표 플러그인 50종 무수정 + 행동 동등성 스위트 통과 |

---

## 2. 코드베이스 현재 상태 (직접 검증된 사실)

⚠️ 과거 문서/탐색 결과에 오류가 있었다. 아래는 **2026-08-22 직접 코드 독해로 검증한 값**이다.

| 항목 | 상태 |
|---|---|
| 빌드 | ✅ **정상** (`paper-server/build/classes/java` 10,788 클래스). "빌드 깨짐/~100에러" 주장은 폐기 |
| MC 베이스 | 26.2 snapshot — mache `io.papermc:mache:26.2-snapshot-6+build.1`, Java 25 툴체인, paperweight 2.0.0-beta.21 |
| README.md 하단 | "빌드 베이스라인 이슈"(criterion rename 등) 섹션은 **낡은 잔재였음 → 본 세션에서 갱신 완료**. `UPSTREAM_BUILD_ISSUES.md` 파일은 존재하지 않음(과거 탐색 오류) |
| 스레딩 | 단일 메인스레드 월드 틱 + 웨이브 기반 월드병렬 엔진(aggressive opt-in). Folia식 리전화 없음 |
| Moonrise | `src/minecraft/java/ca/spottedleaf/` 444파일 인라인 (청크시스템/starlight/collisions/entity_tracker 완비, 청크 처리는 이미 멀티스레드). **ThreadedRegionizer 없음** → Phase 4는 Folia에서 포팅 필요 |
| threadedregions 패키지 | Folia API 호환 shim 5개뿐 (FallbackRegionScheduler 등) |
| 패치 | `paper-server/patches/features/` **224개** (0001-0032 클래식 최적화 / 0033-0202 Meteus fast-path / 0203-0224 AGC alpha) |
| AGC 계층 | `paper-server/src/main/java/io/papermc/paper/agc/` 27클래스 + 테스트 29파일(`AgcTestRunner`) |
| wiring | PaperBootstrap(부트스트랩), MinecraftServer(Governor L1629, 병렬틱 L1843, drain) 모두 연결됨 |
| git 상태 | ⚠️ 미커밋 변경 다수(~211건 + 본 세션 분) — 커밋 전략 결정 필요 (§7) |

---

## 3. 본 세션 완료 작업

### Phase 0 — 결함 수정 + 위생 (✅ 완료, 검증됨)

#### 3-1. 병렬 틱 게이트 버그 (심각도: 高)
- **증상**: `AgcParallelWorldTickEngine.java`의 executeWorldTicks가 `MULTIWORLD_UNLOAD`(safety=BASELINE)로 게이트. 진짜 플래그는 `PARALLEL_WORLD_TICK`(AGGRESSIVE_BUT_SAFE). 결과적으로 **기본 BASELINE 모드에서 월드 2개 이상이면 무조건 병렬 틱 발동** → Bukkit 이벤트가 워커 스레드에서 발사 = "baseline 100% 호환" 계약 위반.
- **수정**: 게이트를 `PARALLEL_WORLD_TICK`로 교체 + 수정 사유 주석. 회귀 테스트 `baselineModeMustNeverEngageParallelTicking` 추가.

#### 3-2. PluginSafetyGuard 허위 브릿지 (심각도: 高)
- **증상**: `ensurePrimaryThread()`/`supplyOnPrimaryThread()`가 "primary thread 브릿지" 주석과 달리 `AgcFoliaTuning.submitAsync`(비동기 스케줄 풀)로 보냄. 메인스레드로 안 감.
- **수정**: 파일 전면 재작성. FIFO 메일박스(`ConcurrentLinkedQueue<PendingOp>`) + `drainMailbox(int)` 추가(primary 전용, off-primary 호출 시 IllegalStateException). `bindPrimaryThread`가 **프로덕션 어디서도 호출되지 않는 공백** 발견 → `MinecraftServer.runServer()` 최상단에서 서버 스레드 바인딩 wired.
- 회귀 테스트 5건 추가 (deferral/drain/future완성/off-primary예외/FIFO순서).

#### 3-3. CrossWorldQueue 이중 drain
- **증상**: 엔진 내부와 `tickChildren` 양쪽에서 drainAll. 게다가 순차 실행 경로(엔진 early-return)는 drain이 아예 없었음.
- **수정**: 엔진 내부 drain 제거. **`tickChildren` post-world-tick 구간이 단일 권위 drain 지점** (CrossWorldQueue + 신규 guard 메일박스 함께).

#### 3-4. 충돌 회피 웨이브 분할
- **증상**: javadoc은 "conflict-free"지만 실제론 순차 chunking — 포탈로 연동된 차원끼리 같은 웨이브 배치 가능.
- **수정**: `partitionIntoWaves(items, maxPerWave, conflicts)` first-fit greedy 오버로드 추가(null 술어 시 기존 chunking 유지). `MinecraftServer.tickChildren`에서 **포탈 패밀리 충돌 술어**(같은 level name = 같은 월드 패밀리) wired.
- 테스트: 충돌 비동시배치/전부충돌 pathological/null 폴백 3건.

#### 3-5. 기타
- 기존 테스트들이 **버그 동작(BASELINE에서 병렬 실행)을 기대값으로 각인** → AGGRESSIVE 명시 설정으로 갱신 + `tearDown` 모드 복원 추가.
- `AgcMassiveStressBenchmark`: 게이트 수정 후 병렬 경로가 안 타게 되므로 runBenchmark 시작 시 AGGRESSIVE 명시 + 종료 시 원복.
- `README.md`(agc): stale 부트스트랩 노트/빌드 이슈 섹션 삭제 → 실제 wiring 목록 + 게이팅 정책 섹션 신설.
- CI `.github/workflows/build.yml`: Build 스탭 뒤 `:paper-server:testAgc` 게이트 추가.

### 검증 증거 (Phase 0)

```
.\gradlew.bat :paper-server:testAgc
→ RESULTS: Total: 155, Passed: 155, Failed: 0   BUILD SUCCESSFUL in 2m 21s
```

### 런타임 부팅 스모크 (✅ 성공)

```
.\gradlew.bat :paper-server:runDevServer -Ppaper.runWorkDir=run-smoke
로그(run-smoke/out.log):
  [bootstrap] AGC Performance Layer initialized successfully (mode: AGC_BASELINE)
  AGC Parallel World Tick Engine initialized with 7 worker threads
  AGC Netty enhancer enabled
  Done (75.347s)!
  World 'world'/'world_nether'/'world_the_end' entered HIBERNATING state after 100 idle ticks
→ 부팅 경로(validate/bootstrap/governor/tick루프/hibernation) 전부 실동작 확인 후 서버 정상 종료
```

### Phase 1 — 로드 하니스 (✅ 코드 완성 + 컴파일 검증, live-fire는 미수행)

신규 독립 모듈 `benchmarks/bot-farm/` (루트 settings에 포함 안 함 — paperweight 빌드 무영향):

| 파일 | 역할 |
|---|---|
| `BotHandle.java` | 봇 추상 인터페이스 (connect/moveToward/swing/chat/runCommand/disconnect) |
| `BotClient.java` | MCProtocolLib 26.2-SNAPSHOT 어댑터. **API 시그니처 javap으로 실검증**, 서드파티 import 이 한 파일에만 존재 |
| `Scenario.java` / `Scenarios.java` | 5 시나리오: dense-combat / redstone-storm / teleport-storm / chunk-gen-storm / login-storm(100/s churn) |
| `BotFarm.java` | 스테거드 조인, 20Hz 시나리오 디스패치, churn 루프 |
| `RconClient.java` | 외부 의존 없는 Source RCON 구현 |
| `MsptProbe.java` | RCON 2초폴링 → p50/p95/p99 수집 → 요약 + SLO게이트(p99≤45ms, 실패 exit 2) |
| `HarnessMain.java` | CLI (--host/--port/--bots/--scenario/--duration-s/--join-rate/--rcon-pass) |

- 의존성: `org.geysermc.mcprotocollib:protocol:26.2-SNAPSHOT` (opencollab snapshots; cloudburstmc/nukkitx 전이의존 저장소 추가 완료). 서버 MC 베이스(26.2)와 일치하는 스냅샷 존재 확인(maven-metadata 조회).
- **컴파일 검증**: `BUILD SUCCESSFUL` (compileJava).
- **미수행**: 실서버 부팅 후 봇 접속 live-fire. BotClient의 play-phase 진입 감지는 ConnectedEvent(TCP 레벨) 기준이라 첫 실측에서 보강 필요할 수 있음.

---

## 4. 로드맵 전체 현황

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 결함수정·문서위생·CI·회귀테스트 | ✅ **완료·검증**(155/155 + 부트스모크) |
| 1 | 프로토콜 봇 하니스 + 시나리오 + MSPT 파이프라인 | 🟡 코드완성/컴파일검증. **live-fire 미수행** |
| 2 | 메모리·GC·IO (ZGC vs G1 실험, palette COW, 히버네이션 3단화 HOT/WARM/COLD, save quota) | ⬜ 미착수 |
| 3 | 네트워크 (tickChildren O(N) 샤딩, Netty 다중화/pinning, encode-once fanout, zstd 오프로드) | ⬜ 미착수 |
| 4 | Folia 리전화 포팅 + Bukkit 호환 퍼사드 (thread-affine 프록시, 플러그인 등급 REGION_SAFE/SHIMMED/MAIN_THREAD_ONLY) | ⬜ 미착수 (최대 공수, ~10-12주) |
| 5 | 바닐라 행동 동등성 인프라 (차등테스트, 틱순서 허용집합 명세) | ⬜ 미착수 |
| 6 | 통합·운영화 (Governor↔budget arbiter, flag 롤아웃 순서, 데드락 감지) | ⬜ 미착수 |

공수 추정: 잔여 약 5~8개월 (1인+AI 기준). Phase 2·3·5는 Phase 4와 병행 가능.

---

## 5. 다음 담당자의 다음 단계 (우선순위)

1. **[즉시] 미커밋 상태 스냅샷 커밋** — 본 세션 변경(소스+benchmarks+CI+run-smoke 제외) 포함. 커밋 안 하면 재작업 리스크.
2. **[Phase 1 완성] live-fire 검증**
   - `runDevServer` 부팅(RCON 켠 server.properties는 `run-smoke/server.properties` 참고)
   - `gradlew.bat -p benchmarks\bot-farm run --args="--host 127.0.0.1 --port 25565 --bots 100 --scenario dense-combat --duration-s 60 --join-rate 10 --rcon-pass bench"`
   - 봇 조인 실패 시: BotClient의 configuration-phase 응답(known packs 등) 확인 필요할 수 있음 — MCProtocolLib이 자동 처리하는지 로그로 확인
   - MsptProbe가 mspt 출력 파싱 못 하면 raw transcript 덤프 후 정규식 조정
3. **Phase 2 착수**: ZGC vs G1 벤치먼트 (봇 하니스로 측정) — JVM 플래그 A/B부터.
4. Phase 3 착수 전 `tickChildren` 후반부(MinecraftServer.java:1872-1894 근처) O(N) 루프 재확인.

---

## 6. 주요 파일 지도

```
paper-server/src/main/java/io/papermc/paper/agc/     AGC 성능계층 27클래스 + README.md
paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java   부팅 wiring (L21-38)
paper-server/src/main/java/io/papermc/paper/command/AgcCommand.java  운영명령
paper-server/src/minecraft/java/net/minecraft/server/MinecraftServer.java
    runServer()      bindPrimaryThread (본 세션 추가)
    tickServer()     Governor 평가 (L1629 근처)
    tickChildren()   히버네이션 필터 → executeWorldTicks(conflict 술어) → 단일 drain 지점
paper-server/src/test/java/io/papermc/paper/agc/     29 테스트파일 (AgcTestRunner로 실행)
paper-server/src/minecraft/java/ca/spottedleaf/moonrise/   Moonrise 인라인 (리전화 없음!)
benchmarks/bot-farm/                                  로드 하니스 (§3 참조)
.github/workflows/build.yml                           CI (testAgc 게이트 추가됨)
run-smoke/                                            부팅 스모크 산출물 (gitignore 대상 권장)
```

## 7. 결정 필요 사항 / 리스크

| 항목 | 내용 | 권고 |
|---|---|---|
| 미커밋 ~211건+ | gradle.properties `updatingMinecraft=true`, AGCScale15-24Plan API 삭제 등 혼합 WIP | 현재 상태 스냅샷 커밋 → 이후 updatingMinecraft 플래그는 릴리즈 빌드(createAgcPaperclipJar) 성공까지 유지 |
| src/minecraft 편집 | 본 세션이 working tree(src/minecraft) 직접 편집 — patch 재생성(rebuildPatches) 워크플로우와의 정합성 확인 필요 | applyPatches 재실행 전 변경분 패치화 방법 확정 |
| 봇 play-phase 감지 | ConnectedEvent는 TCP 레벨 | live-fire에서 조인 완료 감지 보강 |
| mspt 파싱 | Paper 포맷 변화에 민감 | 실패 시 raw dump 로직 이미 포함 |
| aggressive 모드 | PARALLEL_WORLD_TICK은 여전히 이벤트 off-main 발사 | Phase 4 퍼사드 완성 전까지 운영서버 사용 금지 (문서화됨) |
| Virtual Threads 도입 | 틱 패스 사용 시 safepoint 함정 | 금지 (워커풀 모델 유지) |

## 8. 명령어 치트시트

```powershell
# 전체 빌드 + AGC 테스트
.\gradlew.bat :paper-server:testAgc

# 개발 서버 부팅 (RCON 포함 설정은 run-smoke 참고)
.\gradlew.bat :paper-server:runDevServer -Ppaper.runWorkDir=run-smoke

# 봇 하니스
.\gradlew.bat -p benchmarks\bot-farm compileJava
.\gradlew.bat -p benchmarks\bot-farm run --args="--host 127.0.0.1 --port 25565 --bots 100 --scenario dense-combat --duration-s 60 --join-rate 10 --rcon-port 25575 --rcon-pass bench"
```
