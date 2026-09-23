# AGC Benchmark Results

> **Scope of this file: synthetic component benchmarks only.**
> Measured via `.\gradlew.bat :paper-server:testAgc -PagcTestFilter=Benchmark`.
> These loops do NOT start a Minecraft server, do NOT create players, entities, chunks
> or network traffic — they exercise in-memory AGC subsystem data structures. Their
> numbers say nothing about real TPS/MSPT and must not be quoted as server performance.
> Real-server comparative results (Vanilla / Paper / AGC via `benchmarks/bot-farm`) will be
> published here when run artifacts exist.

## Latest run — 2026-09-20 (local dev machine)

Environment: Windows, x86_64, 8 cores, SIMD AVX2, Eclipse Adoptium JDK 25.0.3.
Full unit-test suite on the same commit: **571 tests, 571 passed, 0 failed**
(via `:paper-server:testAgc`; the count is a verification result, not a quality metric).

### AgcUltraScaleStressBenchmark — SYNTHETIC (50 in-memory iterations each)

| Scenario | Simulated scale | Total wall | Avg per-iteration | Status |
|:---|:---|:---|:---|:---|
| "1,000 CCU Mass Combat Storm" | simulated 1 world / 1,000 players / 1,000 entities | 4.79 ms | 0.10 ms | PASS |
| "1,000 CCU Dense Wilderness Roaming" | simulated 1 world / 1,000 players / 3,000 entities | 9.13 ms | 0.18 ms | PASS |
| "1,000 CCU Exploration & Intense Chunk Loading" | simulated 1 world / 1,000 players / 2,000 entities | 30.76 ms | 0.62 ms | PASS |
| "1,000 CCU Standard Wilderness Survival" | simulated 1 world / 1,000 players / 2,500 entities | 16.64 ms | 0.33 ms | PASS |
| "5,000 CCU & 500 Worlds (Mega Server)" | simulated 500 worlds / 5,000 players / 50,000 entities | 34.91 ms | 0.70 ms | PASS |

> "Players", "worlds" and "entities" above are array sizes in the benchmark loop, not
> objects a server ticked. "MSPT" is the loop's per-iteration wall time, not tick time.

### AgcMassiveStressBenchmark — SYNTHETIC (50 in-memory iterations)

| Scenario | Simulated scale | Total wall | Avg per-iteration | Status |
|:---|:---|:---|:---|:---|
| "500 players / 50 worlds (10 active + 40 hibernating)" | simulated 50 worlds / 500 players / 5,000 entities | 138.17 ms | 2.76 ms | PASS |

## Real-server comparative benchmark — NOT YET PUBLISHED

The harness (`benchmarks/bot-farm`) supports Vanilla/Paper/AGC three-way runs with bot
scenarios (`dense-combat`, `redstone-storm`, `teleport-storm`, `chunk-gen-storm`,
`login-storm`). Required methodology for a publishable result:

1. Same machine, same world, same bot script for all three server types.
2. Server jars built from the same commit where possible (Vanilla/Paper from Mojang/PaperMC).
3. Warm-up period excluded, confidence interval reported, weak segments included.
4. Raw log uploaded as a CI artifact; commit hash recorded.

Until such an artifact exists, README and this file make no real-server claims.

## Reproduce

```powershell
.\gradlew.bat :paper-server:testAgc -PagcTestFilter=Benchmark   # synthetic component benchmarks
.\gradlew.bat :paper-server:testAgc                             # full unit-test suite
```

## CI

`.github/workflows/benchmark.yml` runs the Benchmark filter on every push / PR and uploads
the full log as an artifact, plus appends the PASS/FAIL summary to the GitHub Step Summary —
so every commit publishes its bench result on GitHub (synthetic scope as stated above).
