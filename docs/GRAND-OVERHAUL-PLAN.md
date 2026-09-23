# AGC Grand Overhaul Plan — Lossless Innovation, Zero Rigging, Provable Fairness

> Status: DRAFT v1 (2026-09-23), based on commit `acb8480` + the uncommitted honesty pass.
> Scope: architecture overhaul, performance program, honesty enforcement, fair benchmarking,
> release engineering for AGC (Paper 26.2 fork, Java 25).

This plan exists to move AGC from "many optimizations exist" to "every optimization is wired,
provably lossless (or honestly opt-in), and measured on a real server under fair conditions."
It is written against the actual repository state, not aspirations. Where the current state
contradicts a README claim, the claim is treated as a bug to fix, not a fact to repeat.

---

## 0. Non-negotiable principles

| # | Principle | Enforcement mechanism |
|:--|:--|:--|
| P1 | **Lossless by default.** Anything observable to gameplay (AI cadence, physics, redstone timing, chunk cadence, knockback) ships OFF unless it is bit-for-bit or provably behavior-identical. | Default config values + per-key deviation comments + parity tests. |
| P2 | **No dead switches.** Every feature flag has a live production consumer, or is declared in `AgcCapabilityMatrix.DORMANT_FEATURES` and forced to report `false`. | `AgcFeatureWiringAuditTest`, `scripts/agc-wiring-census.py`. |
| P3 | **No lying modes.** `mode: vanilla` must be the exact Paper code path — every AGC gate off, verified by an automated differential run, not by inspection. | `AgcModePolicy.forceVanillaPath` + a parity CI job. |
| P4 | **No synthetic numbers presented as server performance.** In-memory loop benchmarks are labeled synthetic; no TPS/MSPT/CCU claim without a bot-farm run artifact. | README/RESULTS policy + CI artifact requirement. |
| P5 | **Fair measurement.** Vanilla / Paper / AGC on the same machine, same world seed, same bot script, warm-up excluded, confidence intervals reported, raw logs uploaded, weak segments published alongside wins. | `benchmarks/bot-farm` methodology doc + artifacts. |
| P6 | **Reproducible builds.** A clean `git clone` + `./gradlew applyPatches build` must produce the real AGC server. Nothing load-bearing may live only in git-ignored directories or local scripts. | CI build from clean checkout (already the CI model — must be made true). |

---

## 1. Current-state audit (measured, not assumed)

| Area | Fact (verified 2026-09-23) | Verdict |
|:--|:--|:--|
| AGC surface | ~140 classes under `io.papermc.paper.agc`; 46 capability features; 46 gate consumers across 26 NMS files; 170+ boolean fast-path flags in `GlobalConfiguration.Agc.Performance` | Large, real, but unevenly wired |
| Tests | 130 AGC test classes; 571 tests pass via `:paper-server:testAgc`; `test` task fixed to actually include `**/*Test.class` (was silently running almost nothing) | Good base, coverage uneven |
| Honesty infra | `AgcModePolicy` (real `mode: vanilla`), wiring audit test, census script, `agc-honesty-fixes.py` (removes ungated behavior changes: mob AI half-rate gate, brain throttle, villager 1-in-20 tick, observer depth-64 limiter, arrow early-return, orb sleep gating, async pathfinding gating) | Directionally correct, uncommitted |
| **Build integrity** | **`paper-server/src/minecraft/` is git-ignored and untracked.** The entire AGC NMS patch surface exists only in the local working tree + `.nms_backup` (5 files) + one Python script. A clean clone cannot rebuild the current server. | **Critical defect** |
| **Census gate** | `scripts/agc-wiring-census.py` reports **16 features NOT config-authoritative** and **3 dormant** (CHUNK_LOAD_BUDGET, CROSS_WORLD_QUEUE, FAST_REDSTONE_ENGINE); the script's own CI guard returns exit 1 today, i.e. the honesty gate is red. | **Red gate, must go green** |
| Doc/code drift | README calls CROSS_WORLD_QUEUE "✅ Production (INFRA)" while the census proves zero consumers. Feature-status tables in README are hand-maintained. | Drift confirmed |
| Mode naming | Default `mode = "agc_aggressive"` while the marketing line is "lossless by default". The file defaults are lossless, but the name promises aggression and confuses operators and A/B measurements. | Rename needed |
| Benchmarks | Bot-farm harness exists (`benchmarks/bot-farm`: BotClient/BotFarm/HarnessMain/MsptProbe/RconClient, 5 scenarios). **Zero real-server comparative artifacts published.** All published numbers are synthetic in-memory loops (correctly labeled after the honesty pass). | Harness ready, program missing |
| Repo state | 27 modified files + new `AgcModePolicy.java`, `docs/`, `scripts/agc-honesty-fixes.py` uncommitted; `updatingMinecraft=true` in `gradle.properties` | Land before building on top |

---

## 2. Phase plan

### Phase 0 — Structural integrity & landing the honesty pass (Week 0–1)

**Goal: the repository alone can rebuild and validate the server. No local-only state.**

| Task | Detail | Exit criterion |
|:--|:--|:--|
| 0.1 Commit the honesty pass | Land the 27 modified files + `AgcModePolicy.java`, `docs/WIRING.md`, `scripts/agc-honesty-fixes.py` as reviewed, atomic commits. | Working tree clean; CI green. |
| 0.2 Version-control the NMS patch surface | `paper-server/src/minecraft/` must stop being git-ignored **or** every AGC NMS edit must be expressible as data in tracked files. Modern Paper commits this tree; AGC should follow (remove the `.gitignore` line, commit the tree, delete `.nms_backup` once diff-verified). | `git clone` → `applyPatches build testAgc` produces a server whose NMS tree diffs clean against the reference tree. |
| 0.3 Convert `agc-honesty-fixes.py` from rescue script to guard | Once the tree is tracked, the 8 honesty fixes become normal commits; the script becomes a CI check asserting the listed anti-patterns never reappear (e.g. no `(tickCount + id) % 2` AI gate in `Mob.java`). | Script runs in CI in "verify" mode; fails on regression. |
| 0.4 Resolve `updatingMinecraft=true` | Either finish the 26.2 update or record precisely what remains. | Flag reflects reality; update checklist in `docs/`. |

### Phase 1 — Honesty hardening: make "주작 없음" machine-enforced (Week 1–3)

**Goal: every claim an operator can read is backed by code or a test, and the census gate is green in CI.**

| Task | Detail | Exit criterion |
|:--|:--|:--|
| 1.1 Zero unbound features | Give each of the 16 NOT-config-authoritative features (CHUNK_L1_L2_CACHE, FAST_NETWORK_SERIALIZER, HOT_OBJECT_POOLS, JIT_TYPE_DISPATCHER, LIGHT_BATCH_OPTIMIZER, LITHIUM_CHUNK_REGISTER, LOCKFREE_EVENT_DISPATCHER, NETWORK_PACKET_PRIORITY, NETWORK_ZSTD_COMPRESSION, OFFHEAP_SLAB_ALLOCATOR, STRUCTURE_LAYOUT_OPTIMIZER, VECTOR_MATH_ACCELERATOR, …) a real `agc.yml` key via `AgcConfigSync`, or move it to `DORMANT_FEATURES` with justification. | Census exits 0 in CI; `docs/WIRING.md` shows 0 unbound, unacknowledged features. |
| 1.2 Resolve the 3 dormant features | For CHUNK_LOAD_BUDGET, CROSS_WORLD_QUEUE, FAST_REDSTONE_ENGINE: either wire the consumer (fixing the README↔code contradiction for CROSS_WORLD_QUEUE) or delete the feature entry. Dormant-but-documented-as-production is exactly the "주작" pattern this fork rejects. | 0 dormant features, or each has an explicit `DORMANT_FEATURES` justification and README shows 💤. |
| 1.3 Mode taxonomy cleanup | Rename default mode to `lossless`; collapse `agc_baseline`/`unified`/`agc_aggressive` into two real modes: `lossless` (shipped defaults, all flags behavior-preserving) and `experimental` (file is truth, opt-ins allowed). Keep `vanilla` as the control arm. Migration: unknown/legacy mode names warn and map, never silently change behavior. | 3 modes total, each with a one-line honest contract in `agc.yml` header comment and a test (`AgcConfigSyncTest`, `AgcModePolicy` tests). |
| 1.4 Per-flag lossless classification | Classify all 170+ performance flags into `LOSSLESS` (may default ON) vs `BEHAVIOR_RISK` (must default OFF) in the capability matrix, with a test asserting BEHAVIOR_RISK flags are false in a fresh default config. | Classification table generated into docs; audit test enforces defaults. |
| 1.5 README truth-sync | Generate the "Feature Implementation Status" tables from the same census data as `docs/WIRING.md` instead of hand-maintaining them. Remove any row the generator cannot prove. | README tables carry a "generated" banner; diff-check in CI. |
| 1.6 Vanilla-mode differential proof | Automated job: boot AGC with `mode: vanilla` and Paper from the same commit base, run the bot-farm parity scenario (deterministic world seed + scripted actions), compare observable outputs (block states, entity positions over time, redstone timings, drop counts). | Published parity report; CI regression threshold on divergence = 0 for lossless flags. |

### Phase 2 — Lossless algorithm innovation program (Week 2–8, overlapping)

**Goal: real speed from better algorithms, never from doing less game. Every item ships with (a) a parity test proving behavior-equivalence, and (b) a component benchmark proving the win.**

The rule that defines this phase: **an optimization is "lossless" only if the Paper code path and the AGC code path produce identical observable state for identical inputs.** "Players probably won't notice" is not lossless — that category was explicitly deleted by the honesty pass (mob AI gates, brain throttles, observer limiters) and stays deleted.

| Workstream | Candidate innovations (all exist in-tree, need parity evidence) | Parity proof required |
|:--|:--|:--|
| 2.1 Network I/O | Zero-copy broadcast hub (`AgcZeroCopyBroadcastHub`), flush coalescing (`AgcFlushCoalescer`), branchless VarInt/VarLong, static/registry encode caches (`AgcStaticPacketEncodingCache`), chunk send serialization cache | Byte-identical packet streams: record NMS-bound bytes on Paper vs AGC for the same tick workload, diff must be empty. |
| 2.2 Entity & collision | Lithium ports (push-pair dedup, cramming early termination, projectile same-class skip), SoA physics (`AgcSoaEntityPhysicsEngine`), SIMD AABB (`AgcSimdCollisionKernel`, `AgcVectorMath`) | Bit-identical float math or documented reassociation-safe paths; `AgcBehaviorParitySuite` extended to collision predicates and movement outcomes. |
| 2.3 Redstone | Alternate Current DAG evaluator (`AgcAlternateCurrentEvaluator`) | Existing timing-parity tests kept green; add long-run state-machine equivalence fuzz (random wire topologies, N ticks, identical output power levels). |
| 2.4 Chunk & lighting | C2ME pipeline (`AgcC2meChunkPipeline`), RCU chunk map (`AgcLockFreeRcuChunkMap`), StarLight batch (`AgcStarLightBatchOptimizer`), chunk cache hierarchy | Chunk NBT equality after load/save round-trips; light level grids identical to vanilla engine on generated worlds. |
| 2.5 Memory & GC | Off-heap slab allocator (`AgcOffHeapSlabAllocator`), palette COW (`AgcPaletteCowOptimizer`), hot object pools, primitive collections (`AgcPrimitiveCollections`) | Allocation-rate JFR comparison; no off-heap leak under soak test (native memory flat over 24h). |
| 2.6 Worldgen | FastNoise engine, template-pool dedup, jigsaw box octree, structure NBT pruner | **Same seed → byte-identical chunk** for representative biome/structure sets. This is the strictest parity gate; anything short is opt-in. |
| 2.7 Scheduler & tick | Tick budget allocator, block-tick batcher, timing wheel (`AgcTimingWheel`) — ordering-preserving variants only | Scheduled-tick execution order and counts identical to Paper for scripted scenarios. |

**Definition of done per item:** parity test in `:paper-server:testAgc` + synthetic component number recorded in `benchmarks/RESULTS.md` (labeled synthetic) + census row shows config binding + consumer. An item missing any leg does not ship as ✅.

**Explicitly out of scope for default-on (honest opt-in only):** parallel world tick, multiverse hibernation, entity sleep/orb sleep, async pathfinding, tracker throttles, chunk send budgeting, combat sub-tick dispatch. These change observable behavior under some conditions; they remain available, documented per key, default OFF. AGC's position: opt-in knobs are a feature; pretending they are free is the rigging.


### Phase 3 — Fair benchmarking program: numbers that cannot be accused of rigging (Week 4–8)

**Goal: replace synthetic-only marketing with a reproducible, adversarially fair measurement pipeline.**

| Task | Detail | Exit criterion |
|:--|:--|:--|
| 3.1 Harness hardening | Finish `benchmarks/bot-farm`: deterministic world seed, fixed bot scripts per scenario (`dense-combat`, `redstone-storm`, `teleport-storm`, `chunk-gen-storm`, `login-storm`), MSPT/TPS probe via `MsptProbe`, headless client protocol correctness for 26.2. | Harness runs end-to-end on a clean machine from a README command. |
| 3.2 Methodology document | Same machine, same JDK/flags, same world, same bot counts; N≥5 runs per configuration; warm-up trimmed; report median + p95 + confidence interval; record exact commit hashes of all three servers; disclose hardware. | `benchmarks/METHODOLOGY.md` committed; every published table links it. |
| 3.3 Three-way run | Vanilla (Mojang) vs Paper (upstream, same MC version) vs AGC (`mode: lossless`) vs AGC (`mode: experimental`) — four arms. Publish raw logs as artifacts. | First real comparative table in `benchmarks/RESULTS.md`, **including scenarios where AGC loses or ties**. |
| 3.4 CI benchmark tiering | Keep synthetic component benchmarks in PR CI (fast); run the real bot-farm suite nightly/on-tag on a pinned self-hosted runner (hardware variance makes hosted runners unfair). | `benchmark.yml` unchanged for PRs; new `benchmark-real.yml` with artifact retention. |
| 3.5 Anti-rigging audit trail | Each published number carries: commit hash, config files used, JVM flags, raw log path. A third party must be able to reproduce within CI tolerance. | Reproduction script `benchmarks/reproduce.ps1` verified by a fresh-clone run. |

### Phase 4 — Architecture consolidation: 140 classes → coherent subsystems (Week 6–12)

**Goal: the codebase becomes navigable, deletable, and reviewable. Fewer, deeper modules instead of many shallow "engines".**

| Task | Detail | Exit criterion |
|:--|:--|:--|
| 4.1 Subsystem map | Group existing classes into 8 subsystems (network, entity, chunk, redstone, worldgen, memory, tick/scheduler, observability). `AgcFeatureScoper` becomes the single registry; delete duplicate/overlapping implementations (e.g. `AgcHierarchicalActivationRange` vs `…V3` — keep one). | `docs/ARCHITECTURE.md` with ownership per subsystem; dead code deleted, not deprecated forever. |
| 4.2 Wiring consolidation | Today gates are read from NMS via fully-qualified `AgcCapabilityMatrix.isEnabled(...)` sprinkled across 26 files (46 sites). Introduce a thin `AgcGates` facade with per-subsystem accessors so NMS diff noise shrinks and upstream merges get easier. | NMS call-site count reduced; census output stable. |
| 4.3 Naming honesty pass | Rename classes that promise more than they do (anything "Ultra", "Extreme", "Universe" that is a modest optimization). Class names are claims; they must survive the same audit as README claims. | No class name asserts a capability its implementation and tests cannot show. |
| 4.4 Test pyramid | 571 unit tests today; add (a) golden-file parity tests per Phase-2 item, (b) chaos/fault-injection runs (`AgcChaosFaultInjector`) in nightly CI, (c) a smoke boot test (`run-smoke`) in PR CI. | PR CI < 15 min; nightly covers chaos + soak. |

### Phase 5 — Release engineering & upstream tracking (Ongoing)

| Task | Detail | Exit criterion |
|:--|:--|:--|
| 5.1 Release train | Tag `agc-26.2-alpha.N` releases from CI with the paperclip jar, census doc, and benchmark artifacts attached. `packages/` jars stay out of git (already ignored). | Every tag reproducible from CI artifacts. |
| 5.2 Upstream sync cadence | Track `upstream` (PaperMC/Paper) weekly; the tracked NMS tree (Phase 0.2) makes merge conflicts explicit instead of invisible. Re-run the full honesty gate after every upstream merge. | Merge-forward log in `docs/UPSTREAM.md`; gate green post-merge. |
| 5.3 Compatibility surface | Keep plugin-compat verifier (`AgcPluginCompatibilityVerifier`) and safety guard wired; publish a supported-plugin matrix generated from test runs, not anecdotes. | Compatibility matrix regenerated per release. |



---

## 3. KPIs & gates (tracked per release)

| Metric | Baseline (2026-09-23) | Target (end of overhaul) |
|:--|:--|:--|
| Features without config binding | 16 | 0 |
| Dormant features | 3 | 0 (wired, deleted, or justified) |
| Census CI gate | exit 1 (red) | exit 0 enforced on every PR |
| NMS patch surface under version control | 0% | 100% |
| Lossless flags with a parity test | partial (noise/redstone/collision only) | 100% of default-ON flags |
| Real-server comparative benchmark artifacts | 0 | Full 4-arm matrix per release |
| README claims traceable to code/test/artifact | partially | 100% (generated tables) |
| Modes | 6 confusing | 3 honest (`vanilla` / `lossless` / `experimental`) |

## 4. Risk register

| Risk | Impact | Mitigation |
|:--|:--|:--|
| Committing the full NMS tree explodes repo size / merge conflicts with upstream | High | Follow Paper's own convention (they commit it); keep AGC edits behind `// AGC start/end` markers and the `AgcGates` facade to minimize diff surface. |
| Parity tests reveal a beloved "optimization" is not lossless | Medium | It moves to opt-in with honest docs. That is the system working, not failing. |
| Real benchmarks show smaller wins than synthetic loops suggest | Medium | Publish anyway (P5). Credibility is the moat; inflated numbers are how forks die. |
| Bot-farm protocol drift on MC updates | Medium | Pin protocol per MC version in harness; update alongside `updatingMinecraft` work. |
| Windows dev path quirks (non-ASCII path, Gradle argfiles) breaking contributors | Low | Already documented in `gradle.properties`; add to contributor docs. |

## 5. PR honesty checklist — the standing "주작 방지" definition of done

Every performance PR must answer, in the PR body:

1. **Wiring** — which config key gates this, and which production line reads it? (census must stay green)
2. **Lossless or opt-in** — if lossless: which test proves identical observable behavior? If opt-in: is it default-OFF with the deviation documented at the key?
3. **Measurement** — what benchmark shows the win, and is it labeled synthetic or real-server?
4. **Claims** — does any doc/README/class-name change claim more than 1–3 prove? (If yes: fix the claim, not the audit.)
5. **Kill switch** — can an operator turn this off without recompiling, and does `mode: vanilla` turn it off?

---

*This plan is itself subject to the honesty rules: progress is tracked by the gates above, and any phase that slips gets its status written down, not quietly edited away.*

---

## Execution log

### 2026-09-23 — Phase 0.1 landed; CI archaeology uncovered a deeper Phase 0.2

**Done (pushed to `origin/master`):**
- `c68eee93b` build(gradle): wrapper 9.7.0, `:paper-server:test` now runs the real unit tests
- `131c55de6` refactor(agc): the honesty pass (mode policy, lossless gating, thread-guard truth)
- `f4002f81c` docs(honesty): census tooling, NMS honesty-fix guard, README/RESULTS truth-sync, this plan
- `255d0bbdc` build(ci): restored the `gradlew` executable bit (was lost in the 9.7.0 wrapper bump committed from Windows)
- `100575620` build(ci): `updatingMinecraft=false` (26.2 update had landed; flag was masking remote resolution locally)
- `a283b63d1` build(ci): dropped stale `oldPaperCommit` (pointed at `711c5de2`, a commit never pushed to origin — fresh clones died in `setupMacheSources` with `MissingObjectException`)

**Findings that redefine Phase 0.2:**
1. **CI was already red before this session.** The 2026-09-20 runs for `acb848009` failed in ~20s (`./gradlew: Permission denied`). The README's "CI green as of 2026-09-21" was drift, not a measurement — exactly the class of problem this plan exists to stop.
2. **The mache/MC pair is inconsistent:** `mcVersion=26.2` (release) with `mache("io.papermc:mache:26.2-snapshot-6+build.1")`. A fresh machine decompiles the *release* jar, so 20 mache patches fail. Local builds were masked by the paperweight cache (and by `updatingMinecraft=true` using a different resolution path).
3. **Bumping to the release-paired `26.2+build.1` fixes the mache patches (105/105 applied) but 158 of the 1,164 feature-patch files hit failed hunks** — the checked-in patch set was rebased onto 26.2-snapshot-6, not the release. paperweight tolerates hunk failures into a syntactically broken tree (9 compile errors), so "BUILD SUCCESSFUL" can never be trusted without a compile+test gate. (Note: `:paper-server:testAgc` does not depend on `applyPatches`; the setup tasks only re-run when `applyPatches` is invoked explicitly.)
4. **AGC's NMS edits are not in the applied patch set.** All `AGC-*` patches live in `patches/features-quarantined/` (inert by design). The live NMS surface exists only in the untracked tree plus `scripts/agc-honesty-fixes.py`.
5. Local tree was regenerated during diagnosis and then **restored from backup** (`.minecraft-tree-backup-20260923/`, gitignored); `testAgc` is green again (571/571) on the restored tree with the committed config.

**Pending decision (blocks CI green):** how to rebase the NMS surface onto the release-paired mache —
- **A. Adopt upstream's `ver/26.2` patch set** (guaranteed-consistent), re-apply `agc-honesty-fixes.py`, and mark tree-only consumers dormant until re-implemented as proper patches. Fastest honest path; temporarily sheds tree-only features.
- **B. Export the tree-only AGC delta as real feature patches** on the release base (full patch workflow). Preserves every feature; largest effort; turns `features-quarantined` into either real patches or deleted code.
- **C. Defer** — document CI as known-red at `applyPatches` and proceed with Phase 1 work that doesn't touch the NMS tree.
