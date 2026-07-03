package net.minecraft.server;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local read-only interest graph for high-density survival servers.
 * <p>
 * The graph groups players by coarse world/cell keys so network, entity tracker
 * and chunk fanout preparation can share calculations. It never hides entities,
 * changes view distance, or mutates Bukkit-visible state.
 */
public final class AGCInterestGraph {
    public static final AGCInterestGraph INSTANCE = new AGCInterestGraph();

    private final ConcurrentHashMap<UUID, Long> playerCells = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> cellPopulation = new ConcurrentHashMap<>();
    private final AtomicLong observations = new AtomicLong();
    private final AtomicLong fanoutPlans = new AtomicLong();
    private final AtomicLong sharedPlans = new AtomicLong();

    private volatile boolean enabled = true;
    private volatile int cellSizeBlocks = 32;
    private volatile int targetPlayersPerCell = 24;
    private volatile int maxCells = 262_144;
    private volatile long tickSequence;
    private volatile int densestCellPopulation;

    private AGCInterestGraph() {
    }

    public void configure(final boolean enabled, final int cellSizeBlocks, final int targetPlayersPerCell, final int maxCells) {
        this.enabled = enabled;
        this.cellSizeBlocks = Math.max(8, cellSizeBlocks);
        this.targetPlayersPerCell = Math.max(1, targetPlayersPerCell);
        this.maxCells = Math.max(1024, maxCells);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.playerCells.clear();
        this.cellPopulation.clear();
        this.densestCellPopulation = 0;
    }

    public Observation observePlayer(final UUID playerId, final int worldHash, final double x, final double z) {
        if (!this.enabled || playerId == null) {
            return new Observation(false, 0L, 0, "disabled or missing player");
        }
        if (this.cellPopulation.size() >= this.maxCells) {
            return new Observation(false, 0L, 0, "cell cap reached");
        }
        final long key = cellKey(worldHash, x, z, this.cellSizeBlocks);
        final Long previous = this.playerCells.put(playerId, key);
        if (previous != null && previous.longValue() != key) {
            final AtomicLong previousCount = this.cellPopulation.get(previous);
            if (previousCount != null) {
                previousCount.updateAndGet(value -> Math.max(0L, value - 1L));
            }
        }
        final long count = this.cellPopulation.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        this.observations.incrementAndGet();
        this.densestCellPopulation = Math.max(this.densestCellPopulation, saturatedInt(count));
        return new Observation(true, key, saturatedInt(count), count >= this.targetPlayersPerCell ? "dense cell" : "normal cell");
    }

    public FanoutPlan planFanout(final UUID playerId, final int estimatedRecipients, final String reason) {
        this.fanoutPlans.incrementAndGet();
        final Long cell = playerId == null ? null : this.playerCells.get(playerId);
        final int population = cell == null ? 1 : saturatedInt(this.cellPopulation.getOrDefault(cell, new AtomicLong(1L)).get());
        final boolean shared = this.enabled && population >= this.targetPlayersPerCell;
        if (shared) {
            this.sharedPlans.incrementAndGet();
        }
        final int duplicateSavings = shared ? Math.max(0, Math.min(estimatedRecipients, population) - 1) : 0;
        return new FanoutPlan(shared, population, duplicateSavings, shared ? "shared read-only fanout for " + nullToEmpty(reason) : "direct fanout for " + nullToEmpty(reason));
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.tickSequence,
            this.cellSizeBlocks,
            this.targetPlayersPerCell,
            this.maxCells,
            this.playerCells.size(),
            this.cellPopulation.size(),
            this.densestCellPopulation,
            this.observations.get(),
            this.fanoutPlans.get(),
            this.sharedPlans.get(),
            sampleCells(8)
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCInterestGraph{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", cellSize=" + snapshot.cellSizeBlocks()
            + ", players=" + snapshot.players()
            + ", cells=" + snapshot.cells()
            + ", densestCell=" + snapshot.densestCellPopulation()
            + ", targetPlayersPerCell=" + snapshot.targetPlayersPerCell()
            + ", observations=" + snapshot.observations()
            + ", fanoutPlans=" + snapshot.fanoutPlans()
            + ", sharedPlans=" + snapshot.sharedPlans()
            + '}';
    }

    private Map<Long, Long> sampleCells(final int limit) {
        final Map<Long, Long> sample = new HashMap<>();
        int count = 0;
        for (final Map.Entry<Long, AtomicLong> entry : this.cellPopulation.entrySet()) {
            sample.put(entry.getKey(), entry.getValue().get());
            if (++count >= limit) {
                break;
            }
        }
        return Collections.unmodifiableMap(sample);
    }

    public static long cellKey(final int worldHash, final double x, final double z, final int cellSize) {
        final int size = Math.max(1, cellSize);
        final long cellX = floorDiv((long) Math.floor(x), size) & 0x1FFFFFL;
        final long cellZ = floorDiv((long) Math.floor(z), size) & 0x1FFFFFL;
        final long world = worldHash & 0x3FFFFFL;
        return (world << 42) | (cellX << 21) | cellZ;
    }

    private static long floorDiv(final long value, final long divisor) {
        long result = value / divisor;
        if ((value ^ divisor) < 0 && result * divisor != value) {
            --result;
        }
        return result;
    }

    private static int saturatedInt(final long value) {
        if (value <= 0L) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    public record Observation(boolean recorded, long cellKey, int cellPopulation, String reason) {
    }

    public record FanoutPlan(boolean sharedPrepare, int cellPopulation, int duplicateSavings, String reason) {
    }

    public record Snapshot(
        boolean enabled,
        long tickSequence,
        int cellSizeBlocks,
        int targetPlayersPerCell,
        int maxCells,
        int players,
        int cells,
        int densestCellPopulation,
        long observations,
        long fanoutPlans,
        long sharedPlans,
        Map<Long, Long> sampleCells
    ) {
    }
}
