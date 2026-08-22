# AGC JVM & GC Optimization Guide (Phase 2)

> 대상: 5,000 동시접속자 × 500 활성 월드 초대형 인스턴스 (EPYC 9754 128코어, 768GB~1.5TB RAM)

---

## 1. GC 엔진 비교: Generational ZGC vs G1GC

### A. Generational ZGC (권장 — Java 21/25+)
- **특징**: 서브 밀리초(sub-millisecond) 일시정지, 테라바이트급 힙에서도 p99 GC pause < 5ms 유지.
- **장점**: 500개 월드와 5,000명 동시 접속 상태에서 GC Pause로 인한 MSPT 스파이크 완전 제거.
- **권장 런타임 플래그**:
```bash
-XX:+UseZGC
-XX:+ZGenerational
-Xms768G -Xmx768G
-XX:+AlwaysPreTouch
-XX:+UseNUMA
-XX:AllocatePrefetchStyle=3
-XX:+UseVectorStalledOptimizations
-XX:+UnlockDiagnosticVMOptions
-XX:GuaranteedSafepointInterval=0
```

### B. Optimized G1GC (대체 — 32GB~128GB 중소규모 힙)
- **특징**: Throughput 중심 처리. 128GB 이하 힙에서 처리량 극대화.
- **권장 플래그 (Aikar 튜닝의 초대형 확장)**:
```bash
-XX:+UseG1GC
-Xms128G -Xmx128G
-XX:+AlwaysPreTouch
-XX:+ParallelRefProcEnabled
-XX:MaxGCPauseMillis=15
-XX:G1NewSizePercent=30
-XX:G1MaxNewSizePercent=40
-XX:G1ReservePercent=15
-XX:G1HeapRegionSize=32M
-XX:InitiatingHeapOccupancyPercent=45
-XX:G1MixedGCLiveThresholdPercent=85
-XX:G1RSetUpdatingPauseTimePercent=5
```

---

## 2. AGC 메모리 아키텍처 연동 (Phase 2 완료 항목)

1. **3-Tier 월드 라이프사이클 (HOT / WARM / COLD)**
   - **HOT**: 플레이어 존재 월드. 20 TPS 풀 틱.
   - **WARM (RAM Resident)**: 0 플레이어 100틱 초과. 틱 정지, 메모리 상주로 0ms 즉시 복구.
   - **COLD (Disk Evicted)**: 장기 유휴(6000틱 / 5분 초과). 청크 데이터 디스크 세이브 및 메모리 해제, 대형 힙 OOM 방지.

2. **Palette Copy-On-Write (COW) Optimizer**
   - 균일한 청크 섹션(공기, 베드락, 단일 블록)을 공유 불변 싱글톤 팔레트로 병합 (0-bit overhead).
   - 블록 수정 시 투명하게 가변 팔레트로 자동 확장. 수백 메가바이트의 힙 객체 절감.

3. **Storage I/O Governor**
   - 토큰 버킷 기반 비동기 청크 저장 속도 제어 (`AgcStorageIoGovernor`).
   - 500개 월드 동시 저장 시 NVMe I/O 포화 및 메인 스레드 락 경합 방지.

---

## 3. 실서버 부하 검증 (Bot Farm)

```powershell
# 100 봇 팜 실서버 라이브 파이어
.\gradlew.bat -p benchmarks\bot-farm run --args="--host 127.0.0.1 --port 25565 --bots 100 --scenario dense-combat --duration-s 60 --join-rate 25 --rcon-port 25575 --rcon-pass bench"
```
