package net.minecraft.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;

/**
 * Builds a semantic-preserving world tick plan.
 * <p>
 * Full world ticks are only placed in the same wave when plugin semantics allow
 * it. Otherwise AGC still performs cheap immutable planning and keeps the world
 * mutation/event commit order deterministic. This is not a panic switch: it is
 * the normal compatibility algorithm for Bukkit servers where plugins may touch
 * multiple worlds from event callbacks.
 */
public final class AGCWorldConcurrencyPlanner {
    public static final AGCWorldConcurrencyPlanner INSTANCE = new AGCWorldConcurrencyPlanner();

    public enum Mode {
        FULL_PARALLEL_WAVES,
        MIXED_CONFLICT_WAVES,
        DETERMINISTIC_MAIN_THREAD_COMMIT
    }

    private final AtomicLong plansBuilt = new AtomicLong();
    private final AtomicLong fullParallelPlans = new AtomicLong();
    private final AtomicLong mixedPlans = new AtomicLong();
    private final AtomicLong deterministicCommitPlans = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile boolean pluginCallbacksPrimaryThread = true;
    private volatile int minWorlds = 4;
    private volatile Plan lastPlan = Plan.empty();

    private AGCWorldConcurrencyPlanner() {
    }

    public void configure(final boolean enabled, final boolean pluginCallbacksPrimaryThread, final int minWorlds) {
        this.enabled = enabled;
        this.pluginCallbacksPrimaryThread = pluginCallbacksPrimaryThread;
        this.minWorlds = Math.max(1, minWorlds);
    }

    public Plan buildPlan(final ServerLevel[] levels) {
        if (!this.enabled || levels == null || levels.length == 0) {
            final Plan plan = Plan.empty();
            this.lastPlan = plan;
            return plan;
        }
        final ArrayList<WorldNode> nodes = new ArrayList<>(levels.length);
        for (int i = 0; i < levels.length; ++i) {
            final ServerLevel level = levels[i];
            if (level == null) {
                continue;
            }
            nodes.add(new WorldNode(i, safeWorldKey(level), estimateStaticWeight(level)));
        }
        final List<AGCCompatibilityBridge.PluginFinding> findings = AGCCompatibilityBridge.scanInstalledPlugins();
        final AGCWorldWriteIntentGraph.Plan writeIntentPlan = AGCWorldWriteIntentGraph.INSTANCE.build(levels, findings, this.minWorlds, this.pluginCallbacksPrimaryThread);
        final AGCNoInvasionOptimizer.Decision decision = AGCNoInvasionOptimizer.INSTANCE.admitWorldPlan(
            writeIntentPlan,
            AGCThreadTranslator.INSTANCE.snapshot().pendingTasks()
        );
        final Mode mode = switch (writeIntentPlan.mode()) {
            case CONFLICT_FREE_PARALLEL -> Mode.FULL_PARALLEL_WAVES;
            case PARTITIONED_WAVES -> Mode.MIXED_CONFLICT_WAVES;
            case ORDERED_COMMIT_WITH_PARALLEL_PREPARE -> Mode.DETERMINISTIC_MAIN_THREAD_COMMIT;
        };
        final List<List<Integer>> waves = writeIntentPlan.waves();
        if (decision == AGCNoInvasionOptimizer.Decision.CONFLICT_FREE_WAVE && mode == Mode.FULL_PARALLEL_WAVES) {
            this.fullParallelPlans.incrementAndGet();
        } else if (writeIntentPlan.parallelGroups() > 0) {
            this.mixedPlans.incrementAndGet();
        } else {
            this.deterministicCommitPlans.incrementAndGet();
        }
        final int parallelGroups = writeIntentPlan.parallelGroups();
        final Plan plan = new Plan(mode, nodes, waves, writeIntentPlan.hasBlockedPlugin(), writeIntentPlan.hasWarnPlugin(), parallelGroups);
        this.plansBuilt.incrementAndGet();
        this.lastPlan = plan;
        AGCSemanticInvariant.INSTANCE.recordWorldPlan(mode.name(), waves.size(), parallelGroups);
        return plan;
    }

    public boolean allowFullParallelTick(final ServerLevel[] levels) {
        final Plan plan = this.buildPlan(levels);
        return plan.mode() == Mode.FULL_PARALLEL_WAVES
            && plan.parallelGroups() > 0
            && AGCNoInvasionOptimizer.INSTANCE.isConflictFreeWave(AGCNoInvasionOptimizer.INSTANCE.admitWorldPlan(
                AGCWorldWriteIntentGraph.INSTANCE.lastPlan(),
                AGCThreadTranslator.INSTANCE.snapshot().pendingTasks()
            ));
    }

    public Plan lastPlan() {
        return this.lastPlan;
    }

    private static String safeWorldKey(final ServerLevel level) {
        try {
            return String.valueOf(level.dimension().identifier()).toLowerCase(Locale.ROOT);
        } catch (final Throwable ignored) {
            return "unknown-world";
        }
    }

    private static int estimateStaticWeight(final ServerLevel level) {
        try {
            return Math.max(1, level.players().size());
        } catch (final Throwable ignored) {
            return 1;
        }
    }

    private static List<List<Integer>> singletonWaves(final List<WorldNode> nodes) {
        final ArrayList<List<Integer>> waves = new ArrayList<>(nodes.size());
        for (final WorldNode node : nodes) {
            waves.add(Collections.singletonList(node.index()));
        }
        return Collections.unmodifiableList(waves);
    }

    private static List<List<Integer>> balancedPairs(final List<WorldNode> nodes) {
        final ArrayList<List<Integer>> waves = new ArrayList<>((nodes.size() + 1) / 2);
        for (int i = 0; i < nodes.size(); i += 2) {
            final ArrayList<Integer> wave = new ArrayList<>(2);
            wave.add(nodes.get(i).index());
            if (i + 1 < nodes.size()) {
                wave.add(nodes.get(i + 1).index());
            }
            waves.add(Collections.unmodifiableList(wave));
        }
        return Collections.unmodifiableList(waves);
    }

    private static List<Integer> indexes(final List<WorldNode> nodes) {
        final ArrayList<Integer> indexes = new ArrayList<>(nodes.size());
        for (final WorldNode node : nodes) {
            indexes.add(node.index());
        }
        return Collections.unmodifiableList(indexes);
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

    public String statusLine() {
        final Plan plan = this.lastPlan;
        return "AGCWorldConcurrencyPlanner{enabled=" + this.enabled
            + ", primaryThreadPluginCallbacks=" + this.pluginCallbacksPrimaryThread
            + ", minWorlds=" + this.minWorlds
            + ", plansBuilt=" + this.plansBuilt.get()
            + ", fullParallel=" + this.fullParallelPlans.get()
            + ", mixed=" + this.mixedPlans.get()
            + ", deterministicCommit=" + this.deterministicCommitPlans.get()
            + ", lastMode=" + plan.mode()
            + ", waves=" + plan.waves().size()
            + ", parallelGroups=" + plan.parallelGroups()
            + ", writeIntentGraph=" + AGCWorldWriteIntentGraph.INSTANCE.statusLine()
            + '}';
    }

    public record WorldNode(int index, String key, int staticWeight) {
    }

    public record Plan(
        Mode mode,
        List<WorldNode> worlds,
        List<List<Integer>> waves,
        boolean hasBlockedPlugin,
        boolean hasWarnPlugin,
        int parallelGroups
    ) {
        private static Plan empty() {
            return new Plan(Mode.DETERMINISTIC_MAIN_THREAD_COMMIT, Collections.emptyList(), Collections.emptyList(), false, false, 0);
        }
    }
}
