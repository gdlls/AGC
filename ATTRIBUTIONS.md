# Third-Party Attributions & Open-Source Licensing Notice

**AGC (Advanced Gamedev Craft)** is an open-source Minecraft server implementation licensed under the **GNU General Public License v3.0 (GPL-3.0)**. AGC is a downstream fork of [PaperMC / Paper](https://github.com/PaperMC/Paper) and proudly incorporates, builds upon, and adapts high-performance algorithms, architectures, and concepts from across the open-source Minecraft server and modding communities.

This document serves as the official attribution registry and copyright notice record in strict compliance with the licensing requirements of the **GPL-3.0**, **LGPL-3.0**, **MIT**, and **Apache-2.0** licenses under which the upstream and referenced projects are released.

---

## ⚖️ License Compatibility & Compliance

- **GPL-3.0 (Copyleft)**: All components directly derived from or linking to Paper, Folia, Purpur, Gale, Leaf, Pufferfish, Airplane, Petal, DivineMC, SteelMC, UniverseSpigot, Slice, and KnockbackSync are distributed under the terms of the GNU General Public License version 3.0.
- **LGPL-3.0 Compatibility**: In accordance with Sections 2 and 3 of the GNU Lesser General Public License version 3.0, components and algorithmic designs inspired by or adapted from Lithium, Krypton, and Noisium are conveyed under the terms of the GPL-3.0, with full copyright preservation and attribution to their original authors.
- **MIT License Permissiveness**: Components adapted from Alternate Current, C2ME, FerriteCore, and VMP are licensed under the MIT License. In accordance with the MIT License terms, the original copyright notices and permission notices are reproduced in full below.
- **Source Code Availability**: In compliance with Section 6 of GPL-3.0, the complete source code for AGC, including all modifications, adaptations, and build configurations, is openly available at [https://github.com/gdlls/AGC](https://github.com/gdlls/AGC).

---

## 📚 Attributed Projects Registry

### 1. PaperMC / Paper
- **Upstream Repository**: [https://github.com/PaperMC/Paper](https://github.com/PaperMC/Paper)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Copyright**: Copyright © 2016-2026 PaperMC Team and contributors
- **Components & Innovations Adapted**:
  - Base high-performance Minecraft server implementation.
  - Asynchronous chunk loading and chunk scheduling pipeline.
  - Extended Bukkit/Spigot API and Paper-specific event hooks.
  - Configuration infrastructure and patch bundling mechanics.

### 2. Folia (PaperMC)
- **Upstream Repository**: [https://github.com/PaperMC/Folia](https://github.com/PaperMC/Folia)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Copyright**: Copyright © 2023-2026 PaperMC Team (Carl Olsen / Spottedleaf and contributors)
- **Components & Innovations Adapted**:
  - Multi-threaded regionized scheduler architectures (`FoliaAsyncScheduler`, `FoliaGlobalRegionScheduler`, `FoliaEntityScheduler`).
  - Thread-affinity safety guards and thread-safe event dispatch validation.

### 3. Lithium (CaffeineMC)
- **Upstream Repository**: [https://github.com/CaffeineMC/lithium-fabric](https://github.com/CaffeineMC/lithium-fabric)
- **License**: GNU Lesser General Public License v3.0 (LGPL-3.0)
- **Copyright**: Copyright © 2020-2026 CaffeineMC (jellysquid3, 2No2Name, and contributors)
- **Components & Innovations Adapted**:
  - **Zero-Allocation GoalSelector**: Bitset-based fast-evaluation and early-return AI selector preventing lambda and iterator allocation churn.
  - **Voxel Shape & Collision Kernels**: Optimized block bounding box sweeping and collision shape caching patterns.
  - **Fast POI Search**: Point-of-Interest spatial index search pruning.
  - **Hopper Sleep Mechanisms**: Sleepable inventory scanning patterns for blocked/empty containers.

### 4. Alternate Current
- **Upstream Repository**: [https://github.com/SpaceToad/Alternate-Current](https://github.com/SpaceToad/Alternate-Current)
- **License**: MIT License
- **Copyright**: Copyright © 2021-2026 SpaceToad, 2No2Name
- **Components & Innovations Adapted**:
  - **Directed Acyclic Graph (DAG) Redstone Engine (`AgcAlternateCurrentEvaluator`)**: Replaces vanilla's recursive 32-hop cascading block update model with a topological BFS wire network solver, eliminating stack overflows and network freeze while guaranteeing 100% vanilla signal power levels and firing Bukkit `BlockRedstoneEvent`.
- **MIT License Notice**:
  ```text
  Permission is hereby granted, free of charge, to any person obtaining a copy
  of this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in all
  copies or substantial portions of the Software.

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  SOFTWARE.
  ```

### 5. Leaf
- **Upstream Repository**: [https://github.com/Winds-Studio/Leaf](https://github.com/Winds-Studio/Leaf)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Copyright**: Copyright © 2023-2026 Winds-Studio and Leaf contributors
- **Components & Innovations Adapted**:
  - **Asynchronous Pathfinding Pipeline (`AgcAsyncPathfinder`)**: Offloads mob A* path calculations to dedicated worker threads without blocking world ticks.
  - **Sleepable Hopper Optimizations (`AgcSleepableHopperEngine`)**: Event-driven container sleeping with 0-tick wakeup on inventory mutations.
  - **Low-Latency Network Queueing**: TCP connection write-buffer watermark optimizations.

### 6. C2ME (Concurrent Chunk Management Engine)
- **Upstream Repository**: [https://github.com/RelativityMC/C2ME-fabric](https://github.com/RelativityMC/C2ME-fabric)
- **License**: MIT License / LGPL-3.0
- **Copyright**: Copyright © 2021-2026 RelativityMC (Ishland and contributors)
- **Components & Innovations Adapted**:
  - Lock-free Read-Copy-Update (RCU) chunk map patterns (`AgcLockFreeRcuChunkMap`).
  - Asynchronous chunk generation pipeline design and I/O backpressure regulation.

### 7. VMP (Velocity / Very Many Players)
- **Upstream Repository**: [https://github.com/RelativityMC/VMP-fabric](https://github.com/RelativityMC/VMP-fabric)
- **License**: MIT License
- **Copyright**: Copyright © 2021-2026 RelativityMC (Ishland and contributors)
- **Components & Innovations Adapted**:
  - High-density player packet broadcasting optimizations.
  - Zero-copy network broadcast hub architecture (`AgcZeroCopyBroadcastHub`).
  - Bit-level entity tracking dirty masks (`AgcBitLevelDeltaEntityTracker`).

### 8. Noisium / FastNoise
- **Upstream Repository**: [https://github.com/SteveTownsend/Noisium](https://github.com/SteveTownsend/Noisium) / [FastNoise2](https://github.com/Auburn/FastNoise2)
- **License**: LGPL-3.0 / MIT License
- **Copyright**: Copyright © 2023-2026 SteveTownsend; Copyright © 2020 Jordan Peck (Auburn)
- **Components & Innovations Adapted**:
  - Vectorized world generation noise math.
  - Fast permutation table evaluations for biome and terrain generation hotpaths.

### 9. FerriteCore
- **Upstream Repository**: [https://github.com/malte0811/FerriteCore](https://github.com/malte0811/FerriteCore)
- **License**: MIT License
- **Copyright**: Copyright © 2021-2026 malte0811
- **Components & Innovations Adapted**:
  - Blockstate property neighbor table deduplication.
  - Palette compaction reducing JVM heap memory retention for loaded chunk sections.

### 10. Purpur & Gale
- **Upstream Repositories**: [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur) & [GaleMC/Gale](https://github.com/GaleMC/Gale)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Copyright**: Copyright © 2019-2026 PurpurMC Team; Copyright © 2022-2026 GaleMC Team
- **Components & Innovations Adapted**:
  - **Gale Line of Sight (LOS) Cache**: Fast Ray-AABB occlusion caching for entity target acquisition.
  - Micro-optimizations across entity ticking, item despawn batching, and chunk unload handling.

### 11. Pufferfish & Airplane
- **Upstream Repositories**: [pufferfish-gg/Pufferfish](https://github.com/pufferfish-gg/Pufferfish) & [TECHNOVE/Airplane](https://github.com/TECHNOVE/Airplane)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Copyright**: Copyright © 2021-2026 Pufferfish-GG; Copyright © 2020-2022 Kevin Raneri (TECHNOVE)
- **Components & Innovations Adapted**:
  - **SIMD Entity Collision Detection (`AgcSimdCollisionKernel`)**: Vectorized AABB bounding box collision tests.
  - **Hierarchical Activation Range (`AgcHierarchicalActivationRangeV3`)**: Multi-tier LOD activation distance classification.
  - Async mob spawning preparation patterns.

### 12. Petal & DivineMC
- **Upstream Repositories**: [PetalMC/Petal](https://github.com/PetalMC/Petal) & [DivineMC/DivineMC](https://github.com/DivineMC/DivineMC)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Copyright**: Copyright © 2021-2026 PetalMC Team, DivineMC Team
- **Components & Innovations Adapted**:
  - Multi-world parallel world ticking pipeline (`AgcParallelWorldTickEngine`).
  - Asynchronous entity tracker dispatch optimizations.

### 13. SteelMC & UniverseSpigot
- **Upstream Repositories**: [SteelMC](https://github.com/SteelMC) & [UniverseSpigot](https://github.com/UniverseSpigot)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Copyright**: Copyright © 2022-2026 SteelMC Team, UniverseSpigot contributors
- **Components & Innovations Adapted**:
  - High-concurrency Netty broadcast deduplication.
  - Direct buffer object recycling pools (`AgcHotObjectPool`, `AgcDirectBufferPool`).

### 14. Krypton
- **Upstream Repository**: [https://github.com/astei/krypton](https://github.com/astei/krypton)
- **License**: GNU Lesser General Public License v3.0 (LGPL-3.0)
- **Copyright**: Copyright © 2020-2026 Andrew Steinborn (Tux2 / astei)
- **Components & Innovations Adapted**:
  - Netty pipeline flush coalescing (`AgcFlushCoalescer`).
  - Dynamic byte buffer sizing for Minecraft protocol network writes.

### 15. Slice
- **Upstream Repository**: [https://github.com/Cryptite/Slice](https://github.com/Cryptite/Slice)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Copyright**: Copyright © 2022-2026 Cryptite
- **Components & Innovations Adapted**:
  - Entity ticking and AI goal iteration pruning.
  - Memory allocation reduction in world time and weather updates.

### 16. KnockbackSync
- **Upstream Repository**: [https://github.com/casimir-dev/KnockbackSync](https://github.com/casimir-dev/KnockbackSync)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Copyright**: Copyright © 2024-2026 Casimir, caseload
- **Components & Innovations Adapted**:
  - Client-server knockback prediction concepts and network synchronization validation.

---

## 🛡️ Trademark & Non-Endorsement Disclaimers

1. **Minecraft**: "Minecraft" is a registered trademark of Mojang Synergies AB / Microsoft Corporation.
2. **Non-Affiliation**: AGC is an independent open-source project and is **NOT** affiliated with, authorized by, endorsed by, or in any way associated with Mojang Synergies AB, Microsoft Corporation, or any of their affiliates or subsidiaries.
3. **Vanilla EULA Compliance**: Users and operators of AGC are required to comply with the official Minecraft End User License Agreement (EULA) located at [https://www.minecraft.net/eula](https://www.minecraft.net/eula).

---

## 📜 Warranty Disclaimer

IN ACCORDANCE WITH SECTION 15 OF THE GNU GENERAL PUBLIC LICENSE V3.0, THERE IS NO WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW. EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR OTHER PARTIES PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH YOU.
