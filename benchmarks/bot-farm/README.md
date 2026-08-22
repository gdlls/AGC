# AGC Bot Farm — 프로토콜 레벨 로드 하니스 (로드맵 Phase 1)

실제 네트워크 스택(Netty → 직렬화 → 압축 → 틱)을 통과하는 가상 플레이어 봇 팜.
`AgcMassiveStressBenchmark`(in-JVM 시뮬레이션)와 달리 **실서버 대상** 부하测试용.

## 빌드/실행

```powershell
# 컴파일
.\gradlew.bat -p benchmarks\bot-farm compileJava

# 실행 예시: 봇 500명 밀집 전투 시나리오 5분
.\gradlew.bat -p benchmarks\bot-farm run --args="--host 127.0.0.1 --port 25565 --bots 500 --scenario dense-combat --duration-s 300 --join-rate 25 --rcon-port 25575 --rcon-pass bench"
```

사전 조건 (대상 서버):
- `server.properties`: `online-mode=false`, `enable-rcon=true`, `rcon.port=25575`, `rcon.password=<pass>`
- EULA 동의 완료

## 시나리오 (Phase 1-2)

| 이름 | 내용 | 서버 세팅 명령 |
|---|---|---|
| `dense-combat` | 좁은 반경에 다수 봇 밀집, 지속 스윙 | 없음 |
| `redstone-storm` | 운영자가 준비한 회로 구역 관찰 (회로가 부하원) | `gamerule randomTickSpeed 1` |
| `teleport-storm` | 무작위 좌표 `/tp` 연속 발사 | `sendCommandFeedback false` |
| `chunk-gen-storm` | 스펙테이터 비행으로 미생성 청크 돌입 | `gamemode spectator @a` |
| `login-storm` | 목표 rate의 접속/해제 churn (기본 100/s) | 없음 |

## 메트릭 (Phase 1-3)

`MsptProbe`가 RCON으로 2초마다 `mspt` 출력을 수집 → p50/p95/p99 샘플 누적 →
종료 시 요약 리포트 + SLO 게이트(p99 ≤ 45ms). 게이트 실패 시 exit code 2 (CI 연동용).

## 버전 고정

MCProtocolLib은 기본값 `26.2-SNAPSHOT`(opencollab 스냅샷 저장소, 서버 MC 베이스와 일치).
변경: `-PmcprotocolVersion=<tag>`. API 시그니처는 javap으로 검증되어 BotClient에 문서화됨 —
버전 업데이트 시 수정 대상은 이 파일 하나뿐.

## 알려진 한계 (v0.1)

- 봇은 클라이언트 권한 위치 패킷을 신뢰하는 단순 이동만 함 — 충돌/중력 미시뮬레이션
- 공격은 스윙 애니메이션 수준 (엔티티 추적 기반 타겟팅은 v0.2)
- `mspt` 출력 파싱이 Paper 포맷 변화에 민감 — 실패 시 raw 출력 확인 필요
