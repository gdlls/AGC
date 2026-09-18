package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AGC — canary for the {@code PARALLEL_WORLD_TICK} overlap preconditions (2026-09-13).
 *
 * <h2>What was measured</h2>
 * <p>A live dev server was given nine <b>real</b> {@code ServerLevel}s (the default overworld/nether/end
 * plus six independent flat worlds created through {@code Bukkit.createWorld}) with parallel ticking
 * enabled, and the engine's counters were read back:</p>
 *
 * <pre>
 *   [World Engine] Dispatch: parallel=1,712 sequential=1,261 ticks | Waves: 15,408
 *   [World Engine] Concurrency: 0 ticks overlapped (0.0%) | Concurrent waves: 0 (peak 0 worlds)
 *                   | Single-world waves: 15,408 | minWorlds=4
 * </pre>
 *
 * <p>15,408 waves for 1,712 dispatches is ≈9 waves per tick for 9 worlds: every pair conflicted, so
 * every world ran alone on the calling thread. The feature therefore reports activity while delivering
 * zero concurrency. The three facts below explain why, and this test fails the moment any of them stops
 * being true — which is exactly when the bench must be re-run and this file updated.</p>
 *
 * <h2>Preconditions that are NOT met</h2>
 * <ol>
 *   <li>{@code MinecraftServer.tickChildren} decides wave conflicts by comparing
 *       {@code serverLevelData.getLevelName()}.</li>
 *   <li>{@code PaperLevelOverrides.getLevelName()} delegates to the <i>root</i> {@code PrimaryLevelData}
 *       it was attached to, and {@code PaperWorldLoader} attaches that same root data for every
 *       dimension and every API-created world — so <b>every level reports the same name</b> and the
 *       predicate is constant-true. Verified live: {@code world}, {@code world_nether},
 *       {@code world_the_end} and six {@code agcbench_N} worlds all printed {@code levelName=world}.</li>
 *   <li>{@code AgcParallelWorldTickEngine.shouldDeferCrossWorld} has no production caller, so
 *       cross-dimension entity transfer is <b>not</b> deferred to the post-barrier drain. Relaxing the
 *       predicate would therefore let two levels mutate each other's entity lists concurrently.</li>
 * </ol>
 *
 * <h2>How to retire this test</h2>
 * <ol>
 *   <li>Wire a real family key (for example the dimension key triple
 *       {@code minecraft:overworld}/{@code the_nether}/{@code the_end} for the server's own world) and/or
 *       route {@code Entity.changeDimension} through {@code AgcCrossWorldQueue} while a parallel phase is
 *       active.</li>
 *   <li>Delete the matching assertions here, then re-run the multi-world benchmark
 *       and require a non-zero {@code Concurrency} line before advertising the feature as an optimization.</li>
 * </ol>
 */
public class AgcParallelWorldTickOverlapPreconditionsTest {

    private static Path locateServerDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            final Path candidate = dir.resolve("src").resolve("minecraft").resolve("java");
            if (candidate.toFile().isDirectory()) {
                return dir;
            }
            final Path sub = dir.resolve("paper-server");
            if (sub.resolve("src").resolve("minecraft").resolve("java").toFile().isDirectory()) {
                return sub;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate paper-server production roots from "
            + Paths.get("").toAbsolutePath());
    }

    private static String read(final Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** Every production java file (main + minecraft), as searchable text. */
    private static List<Path> productionFiles(final Path serverDir) throws IOException {
        final List<Path> files = new ArrayList<>();
        for (final String root : List.of("src/main/java", "src/minecraft/java")) {
            final Path base = serverDir.resolve(root.replace('/', java.io.File.separatorChar));
            if (!base.toFile().isDirectory()) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            }
        }
        return files;
    }

    private static Path find(final Path serverDir, final String fileName) throws IOException {
        for (final Path p : productionFiles(serverDir)) {
            if (p.getFileName().toString().equals(fileName)) {
                return p;
            }
        }
        throw new IllegalStateException("production file not found: " + fileName);
    }

    @Test
    public void tickLoopConflictPredicateAllowsWorldConcurrency() throws IOException {
        final Path serverDir = locateServerDir();
        final String tickChildren = read(find(serverDir, "MinecraftServer.java"));

        assertFalse(tickChildren.contains("serverLevelData.getLevelName()"),
            "The parallel-tick conflict predicate should no longer compare serverLevelData.getLevelName()"
                + " because every level in Paper reports the same root name, destroying concurrency.");
        assertTrue(tickChildren.contains("a == b"),
            "The parallel-tick conflict predicate should allow distinct worlds to tick concurrently.");
    }

    @Test
    public void crossWorldDeferralIsWiredIntoDimensionTransferPath() throws IOException {
        final Path serverDir = locateServerDir();
        final List<String> callers = new ArrayList<>();
        for (final Path file : productionFiles(serverDir)) {
            if (file.getFileName().toString().equals("AgcParallelWorldTickEngine.java")) {
                continue; // the definition, not a call site
            }
            final String text = read(file);
            if (text.contains("shouldDeferCrossWorld")) {
                callers.add(file.getFileName().toString());
            }
        }

        assertFalse(callers.isEmpty(),
            "shouldDeferCrossWorld must have production callers (Entity.java) so cross-world dimension"
                + " transfers are safely deferred through AgcCrossWorldQueue during a parallel phase.");
        assertTrue(callers.contains("Entity.java"),
            "Entity.java must call shouldDeferCrossWorld during cross-dimension teleportation.");
    }
}
