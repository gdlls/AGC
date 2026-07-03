package net.minecraft.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha18 integrated tick planner.
 *
 * This compiler does not execute Minecraft logic off-thread. It builds a
 * tick-local plan that separates read-only prepare work from Bukkit-visible
 * ordered commits. All lanes report intent into the same plan so chunk,
 * network, entity and plugin helpers cannot accidentally optimise against
 * different assumptions.
 */
public final class AGCUnifiedTickPlanCompiler {
    public static final AGCUnifiedTickPlanCompiler INSTANCE = new AGCUnifiedTickPlanCompiler();

    public enum Lane {
        NETWORK_FANOUT,
        CHUNK_INTENT,
        ENTITY_VISIBILITY,
        WORLD_PHASE,
        PLUGIN_TRANSLATION,
        IO_HELPER
    }

    private final EnumMap<Lane, AtomicLong> requested = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> prepared = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, AtomicLong> ordered = new EnumMap<>(Lane.class);
    private volatile long tickSequence;
    private volatile long lastPrepareBudget;
    private volatile int lastPrepareWaves;
    private volatile int lastOrderedBarriers;
    private volatile String lastReason = "cold";

    private AGCUnifiedTickPlanCompiler() {
        for (final Lane lane : Lane.values()) {
            this.requested.put(lane, new AtomicLong());
            this.prepared.put(lane, new AtomicLong());
            this.ordered.put(lane, new AtomicLong());
        }
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        for (final Lane lane : Lane.values()) {
            this.requested.get(lane).set(0L);
            this.prepared.get(lane).set(0L);
            this.ordered.get(lane).set(0L);
        }
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.lastPrepareWaves = Math.max(1, Math.min(256, processors * 3));
        this.lastOrderedBarriers = 3;
        this.lastPrepareBudget = Math.max(16_384L, processors * 65_536L);
        this.lastReason = "tick-local plan reset";
    }

    public Admission admit(final Lane lane, final long cost, final boolean visibleCommit, final String reason) {
        final Lane safeLane = lane == null ? Lane.PLUGIN_TRANSLATION : lane;
        final long safeCost = Math.max(1L, cost);
        this.requested.get(safeLane).incrementAndGet();
        if (visibleCommit) {
            this.ordered.get(safeLane).incrementAndGet();
            this.lastReason = "visible ordered commit: " + safe(reason);
            return new Admission(false, true, this.lastPrepareWaves, this.lastOrderedBarriers, this.lastReason);
        }
        if (safeCost > this.lastPrepareBudget) {
            this.ordered.get(safeLane).incrementAndGet();
            this.lastReason = "prepare budget reserved for ordered semantics: " + safe(reason);
            return new Admission(false, true, this.lastPrepareWaves, this.lastOrderedBarriers, this.lastReason);
        }
        this.lastPrepareBudget -= safeCost;
        this.prepared.get(safeLane).incrementAndGet();
        this.lastReason = "read-only prepare admitted: " + safe(reason);
        return new Admission(true, false, this.lastPrepareWaves, this.lastOrderedBarriers, this.lastReason);
    }

    public Plan compile(final int players, final int activeWorlds, final int loadedChunks, final int estimatedEntities, final long translatorPending) {
        final int safePlayers = Math.max(1, players);
        final int worlds = Math.max(1, activeWorlds);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int prepareWaves = Math.max(worlds, Math.min(512, processors * 4 + safePlayers / 96));
        final int orderedBarriers = 3 + Math.min(6, worlds / 8);
        final long helperBudget = Math.max(32_768L, (long) processors * 131_072L - Math.max(0L, translatorPending * 16L));
        final boolean thousandPlayerMode = safePlayers >= 1000;
        return new Plan(safePlayers, worlds, Math.max(0, loadedChunks), Math.max(0, estimatedEntities), prepareWaves, orderedBarriers, helperBudget, thousandPlayerMode, "unified no-invasion tick plan");
    }

    public String statusLine() {
        return "AGCUnifiedTickPlanCompiler{tick=" + this.tickSequence
            + ", prepareBudget=" + this.lastPrepareBudget
            + ", prepareWaves=" + this.lastPrepareWaves
            + ", orderedBarriers=" + this.lastOrderedBarriers
            + ", requested=" + snapshot(this.requested)
            + ", prepared=" + snapshot(this.prepared)
            + ", ordered=" + snapshot(this.ordered)
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    private static Map<Lane, Long> snapshot(final EnumMap<Lane, AtomicLong> source) {
        final EnumMap<Lane, Long> copy = new EnumMap<>(Lane.class);
        for (final Map.Entry<Lane, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return copy;
    }

    public record Admission(boolean readOnlyPrepare, boolean orderedCommit, int prepareWaves, int orderedBarriers, String reason) {}
    public record Plan(int players, int activeWorlds, int loadedChunks, int estimatedEntities, int prepareWaves, int orderedBarriers, long helperBudget, boolean thousandPlayerMode, String reason) {}
}
