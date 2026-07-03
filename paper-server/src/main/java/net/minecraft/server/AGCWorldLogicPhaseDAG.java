package net.minecraft.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hard-coded Minecraft-visible logic phase model for AGC scheduling.
 * The DAG permits parallelism only before visible writes: pure reads, fanout
 * planning and conflict graph construction. Redstone, block entities, entity
 * ticks, plugin events and commits remain ordered phase barriers.
 */
public final class AGCWorldLogicPhaseDAG {
    public static final AGCWorldLogicPhaseDAG INSTANCE = new AGCWorldLogicPhaseDAG();

    public enum Phase {
        READ_ONLY_SNAPSHOT,
        PLAYER_INTENT_SAMPLE,
        CHUNK_INTENT_PLAN,
        ENTITY_CANDIDATE_PLAN,
        NETWORK_FANOUT_PLAN,
        WORLD_CONFLICT_GRAPH,
        BLOCK_AND_FLUID_TICK,
        BLOCK_ENTITY_TICK,
        ENTITY_TICK,
        PLUGIN_EVENT,
        ORDERED_COMMIT
    }

    private final EnumMap<Phase, AtomicLong> phaseCounts = new EnumMap<>(Phase.class);
    private final AtomicLong blockedVisible = new AtomicLong();
    private volatile long tickSequence;

    private AGCWorldLogicPhaseDAG() {
        for (final Phase phase : Phase.values()) {
            this.phaseCounts.put(phase, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Decision classify(final Phase phase, final String reason) {
        final Phase safe = phase == null ? Phase.ORDERED_COMMIT : phase;
        this.phaseCounts.get(safe).incrementAndGet();
        if (isVisibleBarrier(safe)) {
            this.blockedVisible.incrementAndGet();
            return new Decision(false, true, safe, "visible Minecraft phase remains ordered: " + safe + " " + safe(reason));
        }
        final AGCScale16ControlLaw.Admission budget = AGCScale16ControlLaw.INSTANCE.claim(AGCScale16ControlLaw.Axis.WORLD_LOGIC_DAG, 1L, reason);
        if (!budget.admitted()) {
            return new Decision(false, true, safe, budget.reason());
        }
        return new Decision(true, false, safe, "read-only phase may prepare concurrently: " + safe);
    }

    public Decision worldPlan(final AGCWorldWriteIntentGraph.Plan plan, final long translatorPending) {
        if (plan == null || translatorPending > 0L && translatorPending > 8192L) {
            return this.classify(Phase.ORDERED_COMMIT, "missing plan or translator pressure");
        }
        if (plan.parallelGroups() <= 0) {
            return this.classify(Phase.ORDERED_COMMIT, "no conflict-free groups");
        }
        return this.classify(Phase.WORLD_CONFLICT_GRAPH, plan.mode().name());
    }

    public String statusLine() {
        return "AGCWorldLogicPhaseDAG{tick=" + this.tickSequence
            + ", phaseCounts=" + snapshot()
            + ", blockedVisible=" + this.blockedVisible.get()
            + ", rule=redstone/blockEntity/entity/plugin phases stay ordered"
            + '}';
    }

    public Map<Phase, Long> snapshot() {
        final EnumMap<Phase, Long> copy = new EnumMap<>(Phase.class);
        for (final Map.Entry<Phase, AtomicLong> entry : this.phaseCounts.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static boolean isVisibleBarrier(final Phase phase) {
        return switch (phase) {
            case BLOCK_AND_FLUID_TICK, BLOCK_ENTITY_TICK, ENTITY_TICK, PLUGIN_EVENT, ORDERED_COMMIT -> true;
            case READ_ONLY_SNAPSHOT, PLAYER_INTENT_SAMPLE, CHUNK_INTENT_PLAN, ENTITY_CANDIDATE_PLAN, NETWORK_FANOUT_PLAN, WORLD_CONFLICT_GRAPH -> false;
        };
    }

    private static String safe(final String value) {
        return value == null ? "" : value;
    }

    public record Decision(boolean concurrentPrepare, boolean orderedBarrier, Phase phase, String reason) {
    }
}
