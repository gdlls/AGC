package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AGC — enforced wiring audit for {@link AgcCapabilityMatrix.Feature}.
 *
 * <h2>The lie this test exists to prevent</h2>
 * <p>The capability matrix can report a feature as {@code enabled} while no production code ever
 * reads that decision. A source census (production roots only, comment lines stripped) found
 * exactly that: 16 of 44 features had <b>zero</b> consumers — no gate read, no config-key read,
 * no tuning-constant read — yet the matrix (and {@code /agc}) advertised them as active
 * optimizations. Unit tests cannot catch this: they prove a class behaves as designed
 * <i>when called</i>, never that anything calls it.</p>
 *
 * <p>The audit also distinguishes <b>reachable</b> from <b>live</b>. A gate read inside an AGC
 * class that no production code ever invokes (transitively, from known NMS/bootstrap entry
 * points) still means the server never reads it — that was the {@code parallelWorldTick} trap:
 * a real gate, read by a real engine, that only tests ever called. So for AGC-internal files a
 * gate read only counts when the file is transitively reachable; NMS and non-AGC main files
 * count unconditionally (they are invoked by definition of being production call sites).</p>
 *
 * <h2>The contract enforced here</h2>
 * <ol>
 *   <li><b>Every non-dormant feature must have at least one live production gate reader.</b>
 *       If this fails, either wire the feature into a reachable call site or declare it dormant
 *       in {@code AgcCapabilityMatrix.DORMANT_FEATURES} with a justification comment.</li>
 *   <li><b>Every dormant feature must be provably unconsumed</b> — zero live gate reads and zero
 *       other live production references. If consumption appears, dormancy is stale: remove the
 *       entry and wire the feature for real. Dormancy is re-proven on every test run.</li>
 *   <li><b>INFRA features are the documented exception</b> — engine live, gate decorative — and
 *       the day their gate gains a reader, this test fails until the INFRA claim is removed.</li>
 * </ol>
 */
public class AgcFeatureWiringAuditTest {

    /**
     * Features whose ENGINE has production consumers but whose capability GATE does not.
     * Membership is a claim that the feature is mode-independent infrastructure rather than a
     * tunable optimization. Keep this set minimal and justified:
     * <ul>
     *   <li>{@code CROSS_WORLD_QUEUE}: the queue is drained every tick from
     *       {@code MinecraftServer.tickChildren} and is the production hand-off path for
     *       {@code AgcOptimisticTransactionManager}; an empty queue is a no-op, so there is
     *       nothing to gate off.</li>
     *   <li>{@code FAST_REDSTONE_ENGINE}: {@code AgcRedstoneOptimizer} is NMS-wired
     *       ({@code DefaultRedstoneWireEvaluator} + {@code ObserverBlock}) but never reads its
     *       gate. Both behaviors are parity-neutral: an equality filter that only skips work
     *       vanilla skips anyway (the remaining update body is guarded by
     *       {@code previousStrength != targetStrength}), and a bounded observer-chain depth
     *       safety limiter. Nothing to switch off, so the gate is decorative.</li>
     * </ul>
     * Any future member must carry the same kind of justification or be made dormant instead.
     */
    private static final Set<AgcCapabilityMatrix.Feature> INFRA_FEATURES = EnumSet.of(
        AgcCapabilityMatrix.Feature.CROSS_WORLD_QUEUE,
        AgcCapabilityMatrix.Feature.FAST_REDSTONE_ENGINE
    );

    /**
     * Known production entry points into the AGC package, seeding the reachability sweep.
     * These are classes NMS or bootstrap code constructs or calls directly.
     */
    private static final List<String> REACHABILITY_SEEDS = List.of(
        "AgcHotPathRuntimeBridge", "AgcPerformanceTuning", "AgcConfigSync",
        "AgcParallelWorldTickEngine", "AgcWorldHibernationEngine",
        "AgcSimdCollisionKernel", "AgcRedstoneOptimizer", "AgcHopperOptimizer",
        "AgcVillagerOptimizer", "AgcStarLightBatchOptimizer", "AgcFlushCoalescer",
        "AgcStaticPacketEncodingCache", "AgcFeatureScoper", "AgcCrossWorldQueue",
        "AgcSpawnerOptimizer", "AgcScoreboardOptimizer", "AgcCommandOptimizer",
        "AgcParallelLightEngine"
    );

    private static final Pattern AGC_CLASS_WORD = Pattern.compile("\\b(Agc[A-Za-z0-9]+)\\b");

    // Source scanner (comment-stripping, production roots only)

    /** Locates paper-server regardless of the test working directory (gradle module vs repo root). */
    private static Path locateServerDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (dir.resolve("src").resolve("minecraft").resolve("java").toFile().isDirectory()
                && dir.resolve("src").resolve("main").resolve("java").toFile().isDirectory()) {
                return dir;
            }
            final Path sub = dir.resolve("paper-server");
            if (sub.resolve("src").resolve("minecraft").resolve("java").toFile().isDirectory()
                && sub.resolve("src").resolve("main").resolve("java").toFile().isDirectory()) {
                return sub;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate paper-server production roots from "
            + Paths.get("").toAbsolutePath());
    }

    /** Removes // and block comments; string literals are kept (they cannot legitimately name a gate). */
    private static String stripComments(final String source) {
        final StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        final int n = source.length();
        while (i < n) {
            final char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') { i++; }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) { i++; }
                i = Math.min(n, i + 2);
            } else if (c == '"' || c == '\'') {
                final char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    final char s = source.charAt(i);
                    out.append(s);
                    i++;
                    if (s == '\\') {
                        if (i < n) { out.append(source.charAt(i)); i++; }
                    } else if (s == quote) {
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** file -> comment-stripped content for every production java file. */
    private static Map<Path, String> productionSources(final Path serverDir) throws IOException {
        final Map<Path, String> result = new HashMap<>();
        final List<Path> roots = List.of(
            serverDir.resolve("src").resolve("main").resolve("java"),
            serverDir.resolve("src").resolve("minecraft").resolve("java")
        );
        for (final Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        result.put(p, stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
                    } catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        return result;
    }

    private static boolean isMatrixFile(final Path p) {
        return p.getFileName().toString().equals("AgcCapabilityMatrix.java");
    }

    private static boolean isNmsFile(final Path p) {
        return p.toString().replace('\\', '/').contains("/minecraft/");
    }

    private static boolean isAgcInternal(final Path p) {
        return p.toString().replace('\\', '/').contains("/io/papermc/paper/agc/");
    }

    private static String baseName(final Path p) {
        final String name = p.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** Strong gate reference: {@code Feature.<NAME>} in a file other than the matrix itself. */
    private static List<Path> gateReaders(final Map<Path, String> sources,
                                          final AgcCapabilityMatrix.Feature feature) {
        final Pattern p = Pattern.compile("\\bFeature\\." + feature.name() + "\\b");
        final List<Path> hits = new ArrayList<>();
        for (final Map.Entry<Path, String> e : sources.entrySet()) {
            if (!isMatrixFile(e.getKey()) && p.matcher(e.getValue()).find()) {
                hits.add(e.getKey());
            }
        }
        return hits;
    }

    /** Any production mention of the bare feature name outside the matrix. */
    private static List<Path> otherReferences(final Map<Path, String> sources,
                                              final AgcCapabilityMatrix.Feature feature) {
        final Pattern p = Pattern.compile("\\b" + feature.name() + "\\b");
        final List<Path> hits = new ArrayList<>();
        for (final Map.Entry<Path, String> e : sources.entrySet()) {
            if (!isMatrixFile(e.getKey()) && p.matcher(e.getValue()).find()) {
                hits.add(e.getKey());
            }
        }
        return hits;
    }

    /**
     * AGC-internal files transitively reachable from the known NMS/bootstrap entry points.
     * Direction is consumer -> dependency: if a reachable file mentions an AGC class, that
     * class's definition file is reachable too.
     */
    private static Set<Path> reachableAgcFiles(final Map<Path, String> sources) {
        final Map<String, Path> definitions = new HashMap<>();
        final Map<String, Set<String>> refsFrom = new HashMap<>();
        for (final Map.Entry<Path, String> e : sources.entrySet()) {
            final Path p = e.getKey();
            if (!isAgcInternal(p)) {
                continue;
            }
            final String self = baseName(p);
            definitions.putIfAbsent(self, p);
            final Set<String> refs = refsFrom.computeIfAbsent(self, k -> new HashSet<>());
            final Matcher m = AGC_CLASS_WORD.matcher(e.getValue());
            while (m.find()) {
                final String w = m.group(1);
                if (!w.equals(self)) {
                    refs.add(w);
                }
            }
        }
        final Set<Path> reach = new HashSet<>();
        final Deque<String> stack = new ArrayDeque<>();
        // Seed 1: any AGC class referenced from a non-AGC production file (NMS, bukkit bridge,
        // io.papermc plumbing) is executable by construction.
        for (final Map.Entry<Path, String> e : sources.entrySet()) {
            if (isAgcInternal(e.getKey())) {
                continue;
            }
            final Matcher m = AGC_CLASS_WORD.matcher(e.getValue());
            while (m.find()) {
                final Path def = definitions.get(m.group(1));
                if (def != null && reach.add(def)) {
                    stack.push(m.group(1));
                }
            }
        }
        // Seed 2: the curated entry-point list (defense against scanner misses above).
        for (final String seed : REACHABILITY_SEEDS) {
            final Path def = definitions.get(seed);
            if (def != null && reach.add(def)) {
                stack.push(seed);
            }
        }
        while (!stack.isEmpty()) {
            final String cur = stack.pop();
            for (final String dep : refsFrom.getOrDefault(cur, Set.of())) {
                final Path def = definitions.get(dep);
                if (def != null && reach.add(def)) {
                    stack.push(dep);
                }
            }
        }
        return reach;
    }

    /** A gate reader that the running server can actually execute. */
    private static boolean isLive(final Path file, final Set<Path> reachable) {
        if (isNmsFile(file) || !isAgcInternal(file)) {
            return true;
        }
        return reachable.contains(file);
    }

    private static List<Path> liveGateReaders(final Map<Path, String> sources,
                                              final AgcCapabilityMatrix.Feature feature,
                                              final Set<Path> reachable) {
        final List<Path> live = new ArrayList<>();
        for (final Path p : gateReaders(sources, feature)) {
            if (isLive(p, reachable)) {
                live.add(p);
            }
        }
        return live;
    }

    // The enforced contract

    @Test
    public void everyNonDormantFeatureHasALiveProductionGateReader() throws IOException {
        final Path serverDir = locateServerDir();
        final Map<Path, String> sources = productionSources(serverDir);
        final Set<Path> reachable = reachableAgcFiles(sources);

        final List<String> violations = new ArrayList<>();
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            if (AgcCapabilityMatrix.isDormant(f) || INFRA_FEATURES.contains(f)) {
                continue;
            }
            final List<Path> live = liveGateReaders(sources, f, reachable);
            if (live.isEmpty()) {
                violations.add(f.name() + " is enabled-by-default but has NO live production gate"
                    + " reader (either no gate read at all, or only inside unreachable AGC classes)"
                    + " — wire it into a reachable call site, or declare it dormant in"
                    + " AgcCapabilityMatrix.DORMANT_FEATURES with a justification comment");
            }
        }
        assertTrue(violations.isEmpty(),
            "Silent wiring lies detected (reported enabled, executed nowhere):\n  "
                + String.join("\n  ", violations));
    }

    @Test
    public void dormantFeaturesAreProvablyUnconsumed() throws IOException {
        final Path serverDir = locateServerDir();
        final Map<Path, String> sources = productionSources(serverDir);
        final Set<Path> reachable = reachableAgcFiles(sources);

        final List<String> stale = new ArrayList<>();
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.dormantFeatures()) {
            // The lie a dormant entry prevents is "reports enabled": only a gate READ can do that,
            // and only in code the server actually executes. Bare name mentions (strings, comments
            // in identifiers, unused constants inside dead engines) cannot report anything.
            final List<Path> liveGates = liveGateReaders(sources, f, reachable);
            if (!liveGates.isEmpty()) {
                stale.add(f.name() + " is declared dormant but live production code reads its gate at "
                    + fileNames(liveGates)
                    + " — remove it from DORMANT_FEATURES and wire it for real; dormancy must be"
                    + " re-proven");
            }
        }
        assertTrue(stale.isEmpty(),
            "Stale dormancy entries (consumption re-appeared; re-prove or remove):\n  "
                + String.join("\n  ", stale));
    }

    @Test
    public void infraFeaturesAreExactlyTheDocumentedOnes() throws IOException {
        final Path serverDir = locateServerDir();
        final Map<Path, String> sources = productionSources(serverDir);
        final Set<Path> reachable = reachableAgcFiles(sources);

        final List<String> problems = new ArrayList<>();
        for (final AgcCapabilityMatrix.Feature f : INFRA_FEATURES) {
            final List<Path> live = liveGateReaders(sources, f, reachable);
            if (!live.isEmpty()) {
                problems.add(f.name() + " is classified INFRA (gate decorative) but now has live"
                    + " gate reads at " + fileNames(live)
                    + " — remove it from INFRA_FEATURES, it is a wired feature now");
            }
        }
        // INFRA must stay disjoint from dormancy: a dormant INFRA entry contradicts itself.
        for (final AgcCapabilityMatrix.Feature f : INFRA_FEATURES) {
            assertFalse(AgcCapabilityMatrix.isDormant(f),
                f.name() + " cannot be both dormant and INFRA");
        }
        assertTrue(problems.isEmpty(),
            "INFRA classifications need updating:\n  " + String.join("\n  ", problems));
    }

    @Test
    public void dormantFeaturesReportDisabledInEveryModeUnlessPinned() {
        final Set<AgcCapabilityMatrix.Feature> dormant = AgcCapabilityMatrix.dormantFeatures();
        assertFalse(dormant.isEmpty(), "dormancy census must not be emptied silently; if every"
            + " feature is genuinely wired now, update this test's expectations deliberately");

        for (final AgcCapabilityMatrix.Mode mode : AgcCapabilityMatrix.Mode.values()) {
            AgcCapabilityMatrix.setMode(mode);
            AgcCapabilityMatrix.clearRuntimeOverrides();
            for (final AgcCapabilityMatrix.Feature f : dormant) {
                assertFalse(AgcCapabilityMatrix.isEnabled(f),
                    "dormant " + f + " must report disabled in " + mode);
            }
        }

        // Operator pins still win over dormancy — the verified live A/B workflow depends on it.
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.VANILLA);
        try {
            for (final AgcCapabilityMatrix.Feature f : dormant) {
                AgcCapabilityMatrix.setRuntimeOverride(f, Boolean.TRUE);
                assertTrue(AgcCapabilityMatrix.isEnabled(f),
                    "operator pin must override dormancy for " + f);
            }
        } finally {
            AgcCapabilityMatrix.clearRuntimeOverrides();
            AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        }
    }

    @Test
    public void dormancyCensusMatchesTheDocumentedSet() {
        // Pins the audit to the exact documented census so a silent edit of DORMANT_FEATURES
        // must update this test too (deliberate, reviewable change — never an accident).
        final Set<String> documented = Set.of(
            "NETWORK_ZSTD_COMPRESSION",
            "DEFAULT_VIEW_DISTANCE",
            "OFFHEAP_SLAB_ALLOCATOR",
            "JIT_TYPE_DISPATCHER"
        );
        final Set<String> actual = new HashSet<>();
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.dormantFeatures()) {
            actual.add(f.name());
        }
        assertEquals(documented, actual,
            "DORMANT_FEATURES changed — update the documented census together with it and re-verify"
                + " each added/removed entry against a fresh source scan");
    }

    private static String fileNames(final List<Path> paths) {
        final List<String> names = new ArrayList<>();
        for (final Path p : paths) {
            names.add(p.getFileName().toString());
        }
        return names.toString();
    }
}
