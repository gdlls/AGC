package net.minecraft.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Tick-local hotspot map for ordinary survival scale planning. */
public final class AGCRegionHotspotMap {
    public static final AGCRegionHotspotMap INSTANCE = new AGCRegionHotspotMap();

    private final ConcurrentHashMap<Long, Cell> cells = new ConcurrentHashMap<>();
    private final AtomicLong observations = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int cellSizeBlocks = 64;
    private volatile int hotspotPlayers = 48;
    private volatile int maxCells = 524_288;
    private volatile long tickSequence;
    private volatile int lastHotspots;

    private AGCRegionHotspotMap() {
    }

    public void configure(final boolean enabled, final int cellSizeBlocks, final int hotspotPlayers, final int maxCells) {
        this.enabled = enabled;
        this.cellSizeBlocks = Math.max(16, cellSizeBlocks);
        this.hotspotPlayers = Math.max(2, hotspotPlayers);
        this.maxCells = Math.max(1024, maxCells);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.cells.clear();
        this.lastHotspots = 0;
    }

    public Observation observe(final String worldKey, final int blockX, final int blockZ, final int players, final int chunks, final int entities) {
        if (!this.enabled) {
            return new Observation(false, false, 0L, "disabled");
        }
        if (this.cells.size() >= this.maxCells) {
            return new Observation(false, false, 0L, "cell cap reached; preserve ordered processing");
        }
        final long key = key(worldKey, Math.floorDiv(blockX, this.cellSizeBlocks), Math.floorDiv(blockZ, this.cellSizeBlocks));
        final Cell cell = this.cells.computeIfAbsent(key, ignored -> new Cell());
        cell.players.addAndGet(Math.max(0, players));
        cell.chunks.addAndGet(Math.max(0, chunks));
        cell.entities.addAndGet(Math.max(0, entities));
        this.observations.incrementAndGet();
        final boolean hotspot = cell.players.get() >= this.hotspotPlayers;
        if (hotspot) {
            this.lastHotspots++;
        }
        return new Observation(true, hotspot, key, hotspot ? "hotspot shared read-only plans" : "normal cell");
    }

    public AGCScaleKernel.Admission claimHotspotWork(final long cellKey, final long units, final String reason) {
        final Cell cell = this.cells.get(cellKey);
        final long multiplier = cell == null ? 1L : Math.max(1L, Math.min(8L, cell.players.get() / Math.max(1, this.hotspotPlayers)));
        return AGCScaleKernel.INSTANCE.claim(AGCScaleKernel.Axis.HOTSPOT_ACCOUNTING, Math.max(1L, units) * multiplier, reason);
    }

    public String statusLine() {
        return "AGCRegionHotspotMap{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", cells=" + this.cells.size()
            + ", hotspots=" + this.lastHotspots
            + ", observations=" + this.observations.get()
            + ", cellSize=" + this.cellSizeBlocks
            + ", hotspotPlayers=" + this.hotspotPlayers
            + '}';
    }

    private static long key(final String worldKey, final int cellX, final int cellZ) {
        long h = worldKey == null ? 0L : worldKey.hashCode();
        h = (h << 32) ^ (cellX & 0xffffffffL);
        h = Long.rotateLeft(h, 21) ^ (cellZ & 0xffffffffL);
        return h;
    }

    private static final class Cell {
        final AtomicLong players = new AtomicLong();
        final AtomicLong chunks = new AtomicLong();
        final AtomicLong entities = new AtomicLong();
    }

    public record Observation(boolean admitted, boolean hotspot, long cellKey, String reason) {
    }
}
