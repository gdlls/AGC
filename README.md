# AGC (Advanced Gamedev Craft)

> **High-Performance Paper Fork for Minecraft 26.2**
> Integrates production-wired NMS patches with lossless algorithmic optimizations from Lithium, Alternate Current, FastNoise, Krypton, and more — behavior-changing features are opt-in, every claim in this file is traceable to code or a test.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net/)
[![Tests](https://img.shields.io/badge/Tests-Passing-brightgreen.svg)]()
[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-green.svg)]()

---

## ⚡ Overview

Traditional Minecraft servers struggle when player counts exceed 200–300, even on high-end hardware. Upstream Paper addresses single-threaded bottlenecks through asynchronous chunk loading, but world ticking, entity physics, network packet broadcasting, and collision checks still heavily constrain the primary thread.

AGC tackles these bottlenecks through **lossless algorithmic innovation** — replacing inefficient vanilla algorithms with cache-friendly, allocation-free, and asymptotically optimal alternatives while preserving Paper behavior. The honesty rules of this fork (enforced by tests, see `AgcFeatureWiringAuditTest` and `scripts/agc-wiring-census.py`):

- **Every feature flag must have a live production call site** — a flag that "reports enabled but executes nowhere" fails CI (dormant features are declared in code with justification).
- **Behavior-affecting features ship OFF by default** — parallel world ticking, multi-world hibernation, the tracker throttle, combat sub-tick dispatch, orb physics sleep, chunk queue budgeting are explicit opt-ins with the deviation documented at each config key.
- **`mode: vanilla` in `config/agc.yml` is a real Paper-path control arm** — it forces every AGC performance flag off at load time, for A/B measurement and triage.
- **Performance claims are labeled** — synthetic component benchmarks are marked synthetic; no real-server CCU/MSPT number is published without a bot-farm run artifact.

Highlights:

- **170 Meteus NMS Fast-Path Patches**: Surgical micro-optimizations across entity tracking, chunk broadcasting, player list iteration, command dispatch, scoreboard updates, and more — each gated by individual config flags.
- **Lithium Collision Engine Port**: Push-pair deduplication, cramming early termination, and projectile same-class skip — eliminating redundant entity collision calculations.
- **Alternate Current Redstone Engine**: DAG topological BFS wire solver replacing vanilla's recursive 32-hop cascade (100% vanilla timing).
- **Zero-Copy Network Broadcast Hub**: Netty `retain()`-based buffer sharing — serialize once, broadcast to N players without N allocations.
- **Netty Flush Coalescing**: Consolidated per-tick channel flushes reducing OS socket syscalls.
- **FastNoise Engine**: Zero-allocation Perlin sampler with doubled permutation tables.
- **Paper & Plugin Compatibility**: Run existing Spigot/Paper plugins without modification; synchronous Bukkit events run on the real primary thread, and `Bukkit.isPrimaryThread()` never lies.
- **Full wiring census**: [`docs/WIRING.md`](docs/WIRING.md) is generated from source on every change — every feature's config binding and every production call site, including dormant ones.

---

## 📋 Feature Implementation Status

> Features marked ✅ are production-wired with confirmed NMS call sites.
> Features marked 💤 are dormant (declared in `AgcCapabilityMatrix.DORMANT_FEATURES` with justification, provably unconsumed).
> Features marked 🧪 are experimental or in development.
> The full generated census (config binding + every production call site per feature) is [`docs/WIRING.md`](docs/WIRING.md).

### Network Pipeline

| Feature | Status | NMS Integration Point | Capability Gate |
|:---|:---|:---|:---|
| Netty Channel Watermark Tuning | ✅ Production | `ServerConnectionListener.java` | `NETWORK_CHANNEL_WATERMARK` |
| Compression Threshold Tuning | ✅ Production | `Connection.java:817` | `NETWORK_COMPRESSION_TUNING` |
| Priority-Based Packet Budget | ✅ Production | `Connection.java:460` | `NETWORK_PACKET_PRIORITY` |
| Flush Coalescing | ✅ Production | `AgcFlushCoalescer` via `ChannelInitializeListenerHolder` | `NETWORK_CHANNEL_WATERMARK` |
| Branchless VarInt/VarLong Codec | ✅ Production | `VarInt.java:39,83`, `VarLong.java:23,73` | `FAST_NETWORK_SERIALIZER` |
| Registry Encoding Cache | ✅ Production | `PacketEncoder.java:60` | `REGISTRY_ENCODING_CACHE` |
| Zero-Copy Broadcast Hub | ✅ Production | `Connection.java` broadcast path | `NETWORK_PACKET_PRIORITY` |
| Zstd Packet Compression | 💤 Dormant | Requires 1.21+ client negotiation | `NETWORK_ZSTD_COMPRESSION` |

### Entity Engine

| Feature | Status | NMS Integration Point | Capability Gate |
|:---|:---|:---|:---|
| Single-Pass Activation Range Sweep | ✅ Production | `ActivationRange.java:147` | `ACTIVATION_RANGE_ITERATE_ONCE` |
| Entity Tracker Idle Skip | ✅ Production | `ChunkMap.java:1079,1440,1671,1756` | `ENTITY_TRACKER_IDLE_SKIP` |
| Hierarchical EAR 3.0 | ✅ Production | `ActivationRange` integration | `ACTIVATION_RANGE_ITERATE_ONCE` |
| Lithium Push-Pair Skip | ✅ Production | `Entity.java:2422` (patch 0225) | `LITHIUM_COLLISION_ENGINE` |
| Lithium Cramming Early Termination | ✅ Production | `LivingEntity.java:4021` (patch 0226) | `LITHIUM_COLLISION_ENGINE` |
| Lithium Projectile Same-Class Skip | ✅ Production | `ProjectileUtil.java:184` (patch 0227) | `LITHIUM_COLLISION_ENGINE` |
| AABB Vectorized Intersection | ✅ Production | `AABB.java:253` | `VECTOR_MATH_ACCELERATOR` |
| Hot Object Pools (AABB) | ✅ Production | `AABB.java:334` | `HOT_OBJECT_POOLS` |
| Villager AI Optimizer | ✅ Production | `Villager.java:902`, `Brain.java:408` | `VILLAGER_AI_OPTIMIZER` |
| Mob Spawner Density Optimizer | ✅ Production | `NaturalSpawner.java:289` | `SPAWNER_DENSITY_OPTIMIZER` |

### Chunk & World

| Feature | Status | NMS Integration Point | Capability Gate |
|:---|:---|:---|:---|
| 3-Tier World Hibernation | ✅ Production (opt-in, default OFF) | `MinecraftServer.java:1896` | `MULTIWORLD_UNLOAD` |
| Adaptive Tracker Throttle | ✅ Production (opt-in via `universe-net-engine`) | `ServerEntity.java:179` | `ENTITY_TRACKING_INTERVAL` |
| Chunk Send Budget | ✅ Production (opt-in) | `PlayerChunkSender.java:115` | `CHUNK_SEND_BUDGET` |
| Chunk Packet Cache | ✅ Production | `AgcChunkSendCacheSupport.java:62` | `CHUNK_PACKET_CACHE` |
| Lithium Hot Chunk Cache | ✅ Production | `ServerChunkCache.java:126,147` | `LITHIUM_CHUNK_REGISTER` |
| Cross-World Queue | ✅ Production (INFRA) | drained every tick from `MinecraftServer.tickChildren` | `CROSS_WORLD_QUEUE` |
| Parallel World Tick | ✅ Production (opt-in, auto-off with plugins) | `MinecraftServer.java:1916` via `AgcParallelWorldTickEngine` | `PARALLEL_WORLD_TICK` |

### Memory & JIT

| Feature | Status | NMS Integration Point | Capability Gate |
|:---|:---|:---|:---|
| Off-Heap Slab Allocator | ✅ Production (maintenance via `universe-net-engine`) | `AgcOffHeapStorage` slab path + per-tick `trimToFit()` via `AgcHotPathRuntimeBridge` | `OFFHEAP_SLAB_ALLOCATOR` |
| JIT Type Dispatcher | ✅ Production | `AgcEntityTickScheduler.shouldTickEntity` (every entity tick decision) | `JIT_TYPE_DISPATCHER` |

### World Generation

| Feature | Status | NMS Integration Point | Capability Gate |
|:---|:---|:---|:---|
| FastNoise SIMD Gradient Noise | ✅ Production | `ImprovedNoise.java:96` | `FAST_NOISE_GENERATOR` |
| FastNoise Zero-Alloc Sampler | ✅ Production | `ImprovedNoise.java:97` | `FAST_NOISE_ENGINE` |
| BoxOctree Jigsaw Intersection Culling | ✅ Production | `JigsawPlacement.java:503,521` (patch 0228) | `JIGSAW_BOX_OCTREE` |
| Template Pool Dedup Skip | ✅ Production | `JigsawPlacement.java:380` | `TEMPLATE_POOL_DEDUP` |
| Structure Bounding Containment Prune | ✅ Production | `JigsawPlacement.java:475` | `STRUCTURE_LAYOUT_OPTIMIZER` |

### Redstone & Block

| Feature | Status | NMS Integration Point | Capability Gate |
|:---|:---|:---|:---|
| Alternate Current DAG Engine | ✅ Production (INFRA) | `DefaultRedstoneWireEvaluator` wire evaluator + `ObserverBlock` chain limiter | `FAST_REDSTONE_ENGINE` |
| Hopper Destination Cache & Dormancy | ✅ Production | `HopperBlockEntity.java:474,497,661,710` | `HOPPER_OPTIMIZER` |
| Explosion Exposure Raycast Cache | ✅ Production | `ServerLevel.java:2162` | `EXPLOSION_COALESCER` |
| Batched StarLight Calculations | ✅ Production | `SWMRNibbleArray.java:39,54`, `DataLayer.java:79,100` | `LIGHT_BATCH_OPTIMIZER` |

### Experimental / Prototypes

| Feature | Status | Notes |
|:---|:---|:---|
| SoA Entity Physics Engine | 🧪 Prototype | Batch helper via `AgcHotPathRuntimeBridge`; not connected to `Entity.move()` |
| Panama FFM Off-Heap Chunk Storage | 🧪 Prototype | Java FFM API demo; not wired to `LevelChunk`/`ChunkAccess` |
| 8x Unrolled AABB Collision Kernel | 🧪 Internal | Pure Java loop unrolling; JIT auto-vectorization dependent |
| Singleplayer-Feel Combat Engine | ✅ Production (opt-in, default OFF) | `LivingEntity.java:2119` sub-tick dispatch; sends an extra mid-tick velocity packet — changes client prediction vs Paper, so it is opt-in |
| C2ME Async Chunk Pipeline | 🧪 Prototype | Bootstrap-only; no NMS chunk I/O dispatch |
| Parallel Light Engine | ✅ Production | `StarLightInterface.java:639` task split + `AgcStarLightBatchOptimizer` coalescing | `PARALLEL_LIGHT_ENGINE` |

---

## 📊 Benchmarking

AGC includes a **real-world bot stress testing harness** for measuring actual server performance under load.

### Running Real Benchmarks

```powershell
# 1. Start the AGC server
.\gradlew.bat runServer

# 2. In a separate terminal, run the bot farm
.\gradlew.bat -p benchmarks/bot-farm run --args="
  --host 127.0.0.1 --port 25565
  --bots 200 --scenario dense-combat
  --duration-s 300 --join-rate 25
  --rcon-port 25575 --rcon-pass bench"
```

### Available Scenarios

| Scenario | Description |
|:---|:---|
| `dense-combat` | Hundreds of players packed in a small radius with constant melee swings |
| `redstone-storm` | Bots observe an active circuit area (supply a prepared world) |
| `teleport-storm` | Continuous `/tp` churn to random coordinates |
| `chunk-gen-storm` | Spectators flying into ungenerated terrain |
| `login-storm` | Join/leave churn at configurable rate (default: 100/s) |

### Component Integration Benchmarks (synthetic)

AGC also includes internal component throughput benchmarks (synthetic, not real-server):

```powershell
.\gradlew.bat :paper-server:testAgc -PagcTestFilter=Benchmark
```

> **Note**: These measure AGC subsystem integration performance in isolation (hibernation throughput, SoA physics step time, zero-copy broadcast efficiency). They are NOT comparative server benchmarks and do not represent real-world TPS/MSPT under actual gameplay conditions.

### 📈 Verification Status (2026-09-21 honesty pass)

**Real-server (Vanilla / Paper / AGC) comparative bot-farm benchmark results are not yet published.** The harness exists (`benchmarks/bot-farm`, scenarios below), but no run artifacts for this commit have been recorded yet, so no CCU/MSPT/TPS claims are made in this README.

What is verified on every CI run:

- **Full AGC unit-test suite** via `:paper-server:testAgc` (currently 571 passing tests; count printed in the CI log, not asserted as a marketing number).
- **Wiring audit** — every feature flag must have a live production gate reader (`AgcFeatureWiringAuditTest`), and the dormancy census is pinned (`docs/WIRING.md` regenerated by `scripts/agc-wiring-census.py`).
- **Parity suite** — bit-identical math across feature gates (noise, redstone timing, collision predicates) where applicable.
- **Synthetic component benchmarks** (`-PagcTestFilter=Benchmark`) — labeled synthetic, never presented as server TPS/MSPT.

When a real comparative run (Vanilla vs Paper vs AGC, same world/bots/hardware) lands, its raw log artifact and methodology will be linked here — including the weak spots, not just the wins.

<details>
<summary>Why the previously published CCU table was removed</summary>

Earlier revisions of this README presented numbers like "1,000 CCU @ 0.10 ms MSPT / 20.00 TPS" from `AgcUltraScaleStressBenchmark` / `AgcMassiveStressBenchmark`. Those benchmarks never start a server, create players, entities, chunks or network traffic — they loop over in-memory data structures. Presenting their output as server performance was wrong. The numbers remain visible in [`benchmarks/RESULTS.md`](benchmarks/RESULTS.md) with synthetic labels for what they actually measure.
</details>

---

## 🚀 Quick Start & Installation

AGC is a **drop-in replacement target** for Paper 26.2: same jar layout, same config surfaces, plugins load unmodified.

1. Download the latest `agc-paperclip-26.2.local-SNAPSHOT.jar` from [Releases](https://github.com/gdlls/AGC/releases).
2. Replace your existing `paper.jar` or `server.jar` with the AGC jar.
3. Start the server as you normally would. Lossless optimizations are active automatically; behavior-affecting ones are opt-ins in `config/agc.yml` (all default OFF, documented at each key).
4. To compare AGC against exact Paper behavior, set `mode: vanilla` in `config/agc.yml` — every AGC performance path is forced off.

---

## 🛠️ Building from Source

### Prerequisites
- JDK 25 (Adoptium, GraalVM, or Amazon Corretto recommended)
- Git

### Build Instructions
```powershell
# Clone the repository
git clone https://github.com/gdlls/AGC.git
cd AGC

# Build the runnable paperclip server JAR
.\gradlew.bat :paper-server:createAgcPaperclipJar

# Run the dedicated test suite
.\gradlew.bat :paper-server:testAgc
```
The compiled, runnable server JAR will be located at:
`paper-server/build/libs/agc-paperclip-26.2.local-SNAPSHOT.jar` (or in `packages/`)

---

## 📜 License & Attributions

AGC is open-source software licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for the full license text.

For a comprehensive breakdown of all third-party components, copyright notices, and license compliance details, refer to **[ATTRIBUTIONS.md](ATTRIBUTIONS.md)** and **[NOTICE](NOTICE)**.

AGC proudly incorporates, builds upon, and adapts high-performance architectures, algorithms, and concepts from the open-source Minecraft community:

| Project | License | Upstream Author(s) / Organization | Key Innovations Adapted in AGC |
| :--- | :--- | :--- | :--- |
| **[PaperMC / Paper](https://github.com/PaperMC/Paper)** | GPL-3.0 | PaperMC Team | Upstream high-performance server base, async chunk pipeline, Bukkit/Spigot API |
| **[Folia](https://github.com/PaperMC/Folia)** | GPL-3.0 | PaperMC Team (Carl Olsen / Spottedleaf) | Regionized multi-threading concepts, async schedulers, thread-affinity safety guards |
| **[Lithium](https://github.com/CaffeineMC/lithium-fabric)** | LGPL-3.0 | CaffeineMC (jellysquid3, 2No2Name) | Push-pair deduplication, cramming early termination, fast POI spatial index, GoalSelector bitset |
| **[Alternate Current](https://github.com/SpaceToad/Alternate-Current)** | MIT | SpaceToad, 2No2Name | Directed Acyclic Graph (DAG) topological BFS redstone wire evaluator (100% vanilla timing) |
| **[Leaf](https://github.com/Winds-Studio/Leaf)** | GPL-3.0 | Winds-Studio / Leaf Team | Asynchronous pathfinding worker pool, event-driven hopper optimization concepts |
| **[C2ME](https://github.com/RelativityMC/C2ME-fabric)** | MIT / LGPL-3.0 | RelativityMC (Ishland) | Lock-free RCU chunk map architecture, asynchronous chunk generation & I/O pipelines |
| **[VMP](https://github.com/RelativityMC/VMP-fabric)** | MIT | RelativityMC (Ishland) | Zero-copy Netty broadcast hub concepts, bit-level delta entity tracking dirty masks |
| **[Noisium / FastNoise](https://github.com/SteveTownsend/Noisium)** | LGPL-3.0 / MIT | SteveTownsend, Jordan Peck | Vectorized noise generation, fast permutation table math for world generation |
| **[FerriteCore](https://github.com/malte0811/FerriteCore)** | MIT | malte0811 | Memory footprint reduction, blockstate palette neighbor table deduplication |
| **[Purpur](https://github.com/PurpurMC/Purpur) & [Gale](https://github.com/GaleMC/Gale)** | GPL-3.0 | PurpurMC & GaleMC Teams | Gale Line-of-Sight (LOS) occlusion cache, entity activation range micro-optimizations |
| **[Pufferfish](https://github.com/pufferfish-gg/Pufferfish) & [Airplane](https://github.com/TECHNOVE/Airplane)** | GPL-3.0 | Pufferfish-GG, Kevin Raneri | SoA AABB collision layout, Hierarchical Activation Range (EAR) |
| **[Petal](https://github.com/PetalMC/Petal) & [DivineMC](https://github.com/DivineMC/DivineMC)** | GPL-3.0 | PetalMC & DivineMC Teams | Multi-world parallel ticking pipeline, asynchronous entity tracker optimizations |
| **[SteelMC](https://github.com/SteelMC) & [UniverseSpigot](https://github.com/UniverseSpigot)** | GPL-3.0 | SteelMC & UniverseSpigot contributors | Ultra-scale Netty packet broadcast deduplication, direct memory buffer pooling |
| **[Krypton](https://github.com/astei/krypton)** | LGPL-3.0 | Andrew Steinborn (Tux2) | Netty pipeline flush coalescing, dynamic byte buffer sizing |
| **[Slice](https://github.com/Cryptite/Slice)** | GPL-3.0 | Cryptite | Entity tick optimizations, memory allocation reduction in world time & weather loops |

---

### 🛡️ Legal & Trademark Disclaimers

- **Minecraft**: "Minecraft" is a registered trademark of Mojang Synergies AB / Microsoft. AGC is an independent open-source software project and is **not** affiliated with, endorsed by, or associated with Mojang Synergies AB or Microsoft.
- **EULA Compliance**: All users and server operators using AGC must adhere to the official [Minecraft End User License Agreement (EULA)](https://www.minecraft.net/eula).
