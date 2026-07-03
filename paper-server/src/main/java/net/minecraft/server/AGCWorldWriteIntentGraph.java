package net.minecraft.server;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;

/**
 * Conflict graph for multi-world scheduling.
 * <p>
 * Worlds are parallel candidates only when AGC can keep Bukkit-visible writes,
 * scheduled tasks, portals/cross-world transfers and plugin callbacks on their
 * deterministic commit lanes. Risky plugins do not cause a panic switch; they
 * simply add write-intent edges that serialise the affected worlds while allowing
 * read-only preparation elsewhere.
 */
public final class AGCWorldWriteIntentGraph {
    public static final AGCWorldWriteIntentGraph INSTANCE = new AGCWorldWriteIntentGraph();

    public enum Mode {
        CONFLICT_FREE_PARALLEL,
        PARTITIONED_WAVES,
        ORDERED_COMMIT_WITH_PARALLEL_PREPARE
    }

    private final AtomicLong plans = new AtomicLong();
    private final AtomicLong conflictFreePlans = new AtomicLong();
    private final AtomicLong partitionedPlans = new AtomicLong();
    private final AtomicLong orderedPreparePlans = new AtomicLong();
    private volatile Plan lastPlan = Plan.empty();

    private AGCWorldWriteIntentGraph() {
    }

    public Plan build(final ServerLevel[] levels, final List<AGCCompatibilityBridge.PluginFinding> findings, final int minWorlds, final boolean primaryThreadPluginCallbacks) {
        if (levels == null || levels.length == 0) {
            this.lastPlan = Plan.empty();
            return this.lastPlan;
        }
        final ArrayList<Node> nodes = new ArrayList<>(levels.length);
        for (int i = 0; i < levels.length; ++i) {
            final ServerLevel level = levels[i];
            if (level != null) {
                nodes.add(new Node(i, key(level), weight(level)));
            }
        }
        if (nodes.isEmpty()) {
            this.lastPlan = Plan.empty();
            return this.lastPlan;
        }
        final BitSet[] edges = new BitSet[nodes.size()];
        for (int i = 0; i < edges.length; ++i) {
            edges[i] = new BitSet(nodes.size());
        }

        boolean blocked = false;
        boolean warned = false;
        if (findings != null) {
            for (final AGCCompatibilityBridge.PluginFinding finding : findings) {
                if (finding.risk() == AGCCompatibilityBridge.Risk.BLOCKED) {
                    blocked = true;
                } else if (finding.risk() == AGCCompatibilityBridge.Risk.WARN) {
                    warned = true;
                }
            }
        }

        if (nodes.size() < Math.max(1, minWorlds)) {
            connectAll(edges);
        }
        if (primaryThreadPluginCallbacks && (blocked || warned)) {
            connectAll(edges);
        } else if (warned) {
            connectSameFamily(nodes, edges);
        }

        final List<List<Integer>> waves = colourToWaves(nodes, edges);
        final int parallelGroups = countParallelGroups(waves);
        final Mode mode = parallelGroups > 0 && edgeCount(edges) == 0
            ? Mode.CONFLICT_FREE_PARALLEL
            : parallelGroups > 0 ? Mode.PARTITIONED_WAVES : Mode.ORDERED_COMMIT_WITH_PARALLEL_PREPARE;

        switch (mode) {
            case CONFLICT_FREE_PARALLEL -> this.conflictFreePlans.incrementAndGet();
            case PARTITIONED_WAVES -> this.partitionedPlans.incrementAndGet();
            case ORDERED_COMMIT_WITH_PARALLEL_PREPARE -> this.orderedPreparePlans.incrementAndGet();
        }
        final Plan plan = new Plan(mode, Collections.unmodifiableList(nodes), waves, edgeCount(edges), blocked, warned, parallelGroups);
        this.plans.incrementAndGet();
        this.lastPlan = plan;
        return plan;
    }

    public Plan lastPlan() {
        return this.lastPlan;
    }

    public String statusLine() {
        final Plan plan = this.lastPlan;
        return "AGCWorldWriteIntentGraph{plans=" + this.plans.get()
            + ", conflictFree=" + this.conflictFreePlans.get()
            + ", partitioned=" + this.partitionedPlans.get()
            + ", orderedPrepare=" + this.orderedPreparePlans.get()
            + ", lastMode=" + plan.mode()
            + ", worlds=" + plan.nodes().size()
            + ", waves=" + plan.waves().size()
            + ", conflictEdges=" + plan.conflictEdges()
            + ", parallelGroups=" + plan.parallelGroups()
            + ", blocked=" + plan.hasBlockedPlugin()
            + ", warned=" + plan.hasWarnPlugin()
            + '}';
    }

    private static void connectAll(final BitSet[] edges) {
        for (int i = 0; i < edges.length; ++i) {
            for (int j = i + 1; j < edges.length; ++j) {
                edges[i].set(j);
                edges[j].set(i);
            }
        }
    }

    private static void connectSameFamily(final List<Node> nodes, final BitSet[] edges) {
        for (int i = 0; i < nodes.size(); ++i) {
            for (int j = i + 1; j < nodes.size(); ++j) {
                if (family(nodes.get(i).key()).equals(family(nodes.get(j).key()))) {
                    edges[i].set(j);
                    edges[j].set(i);
                }
            }
        }
    }

    private static List<List<Integer>> colourToWaves(final List<Node> nodes, final BitSet[] edges) {
        final ArrayList<Node> ordered = new ArrayList<>(nodes);
        ordered.sort(Comparator.comparingInt(Node::staticWeight).reversed().thenComparing(Node::index));
        final ArrayList<ArrayList<Integer>> waves = new ArrayList<>();
        for (final Node node : ordered) {
            boolean placed = false;
            for (final ArrayList<Integer> wave : waves) {
                boolean conflicts = false;
                for (final int otherIndex : wave) {
                    final int otherNodeOrdinal = ordinalForIndex(nodes, otherIndex);
                    final int thisOrdinal = ordinalForIndex(nodes, node.index());
                    if (thisOrdinal >= 0 && otherNodeOrdinal >= 0 && edges[thisOrdinal].get(otherNodeOrdinal)) {
                        conflicts = true;
                        break;
                    }
                }
                if (!conflicts) {
                    wave.add(node.index());
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                final ArrayList<Integer> wave = new ArrayList<>();
                wave.add(node.index());
                waves.add(wave);
            }
        }
        final ArrayList<List<Integer>> immutable = new ArrayList<>(waves.size());
        for (final ArrayList<Integer> wave : waves) {
            wave.sort(Integer::compareTo);
            immutable.add(Collections.unmodifiableList(wave));
        }
        return Collections.unmodifiableList(immutable);
    }

    private static int ordinalForIndex(final List<Node> nodes, final int index) {
        for (int i = 0; i < nodes.size(); ++i) {
            if (nodes.get(i).index() == index) {
                return i;
            }
        }
        return -1;
    }

    private static int edgeCount(final BitSet[] edges) {
        int count = 0;
        for (final BitSet edge : edges) {
            count += edge.cardinality();
        }
        return count / 2;
    }

    private static int countParallelGroups(final List<List<Integer>> waves) {
        int groups = 0;
        for (final List<Integer> wave : waves) {
            if (wave.size() > 1) {
                groups++;
            }
        }
        return groups;
    }

    private static String key(final ServerLevel level) {
        try {
            return String.valueOf(level.dimension().identifier()).toLowerCase(Locale.ROOT);
        } catch (final Throwable ignored) {
            return "unknown-world";
        }
    }

    private static int weight(final ServerLevel level) {
        try {
            return Math.max(1, level.players().size());
        } catch (final Throwable ignored) {
            return 1;
        }
    }

    private static String family(final String key) {
        final int colon = key.indexOf(':');
        final String tail = colon >= 0 ? key.substring(colon + 1) : key;
        final int underscore = tail.indexOf('_');
        return underscore >= 0 ? tail.substring(0, underscore) : tail;
    }

    public record Node(int index, String key, int staticWeight) {
    }

    public record Plan(Mode mode, List<Node> nodes, List<List<Integer>> waves, int conflictEdges, boolean hasBlockedPlugin, boolean hasWarnPlugin, int parallelGroups) {
        private static Plan empty() {
            return new Plan(Mode.ORDERED_COMMIT_WITH_PARALLEL_PREPARE, Collections.emptyList(), Collections.emptyList(), 0, false, false, 0);
        }
    }
}
