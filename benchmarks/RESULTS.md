# AGC Benchmark Results

> Measured via `.\gradlew.bat :paper-server:testAgc -PagcTestFilter=Benchmark`.
> Component integration benchmarks (synthetic subsystem load, not a live server with real clients).
> They validate AGC subsystem integration under simulated load — hibernation throughput,
> zero-copy broadcast efficiency, parallel world ticking, SoA physics step time.
> They are NOT Vanilla-vs-Paper comparisons and do not represent real-world TPS/MSPT
> under actual gameplay. For real-server load testing, use `benchmarks/bot-farm`.

## Latest run — 2026-09-20 (local dev machine)

Environment: Windows, x86_64, 8 cores, SIMD AVX2, Eclipse Adoptium JDK 25.0.3.
Full suite on the same commit: **569 tests, 569 passed, 0 failed**.

### AgcUltraScaleStressBenchmark (50 ticks each, target < 25.0 ms MSPT @ 20 TPS)

| Scenario | Worlds | Players (CCU) | Entities | Total wall | Avg MSPT | Eff. TPS | Key savings | Status |
|:---|:---|:---|:---|:---|:---|:---|:---|:---|
| 1,000 CCU Mass Combat Storm | 1 | 1,000 | 1,000 | 4.79 ms | 0.10 ms | 20.00 | 499,500 zero-copy serializations saved | PASS |
| 1,000 CCU Dense Wilderness Roaming | 1 | 1,000 | 3,000 | 9.13 ms | 0.18 ms | 20.00 | 499,500 zero-copy serializations saved | PASS |
| 1,000 CCU Exploration & Intense Chunk Loading | 1 | 1,000 | 2,000 | 30.76 ms | 0.62 ms | 20.00 | 199,800 zero-copy serializations saved | PASS |
| 1,000 CCU Standard Wilderness Survival | 1 | 1,000 | 2,500 | 16.64 ms | 0.33 ms | 20.00 | 199,800 zero-copy serializations saved | PASS |
| 5,000 CCU & 500 Worlds (Mega Server) | 500 (HOT 50 / WARM 450) | 5,000 | 50,000 | 34.91 ms | 0.70 ms | 20.00 | 20,700 world ticks saved, 2,499,500 serializations saved | PASS |

### AgcMassiveStressBenchmark (50 ticks, target < 25.0 ms MSPT)

| Scenario | Worlds | Players | Entities | Total wall | Avg MSPT | Eff. TPS | Key savings | Status |
|:---|:---|:---|:---|:---|:---|:---|:---|:---|
| 500 players / 50 worlds (10 active + 40 hibernating) | 50 | 500 | 5,000 | 138.17 ms | 2.76 ms | 20.00 | 1,760 world ticks saved, 24,950 serializations saved, 126,000 AI goals skipped, 24,999 hot objects recycled | PASS |

## Reproduce

```powershell
.\gradlew.bat :paper-server:testAgc -PagcTestFilter=Benchmark
.\gradlew.bat :paper-server:testAgc   # full suite (569 tests)
```

## CI

`.github/workflows/benchmark.yml` runs the Benchmark filter on every push / PR and uploads
the full log as an artifact, plus appends the PASS/FAIL summary to the GitHub Step Summary —
so every commit publishes its bench result on GitHub.
