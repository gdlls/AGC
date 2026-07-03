package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** FIFO-preserving chunk storm smoother for high-player survival exploration. */
public final class AGCChunkStormController {
    public static final AGCChunkStormController INSTANCE = new AGCChunkStormController();

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong smoothed = new AtomicLong();
    private volatile long tickSequence;
    private volatile int maxStormIndex = 8192;
    private volatile String lastReason = "cold";

    private AGCChunkStormController() {
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.maxStormIndex = 8192 + (int) Math.min(8192L, sequence % 8192L);
        this.lastReason = "reset maxStormIndex=" + this.maxStormIndex;
    }

    public Plan plan(final UUID playerId, final AGCChunkBudget.Operation operation, final int radius, final int nearbyPlayers, final String reason) {
        this.planned.incrementAndGet();
        final int safeRadius = Math.max(1, radius);
        final int safeNearby = Math.max(1, nearbyPlayers);
        final int opWeight = operation == AGCChunkBudget.Operation.GENERATE ? 4 : operation == AGCChunkBudget.Operation.LOAD ? 2 : 1;
        final int stormIndex = safeRadius * safeRadius * opWeight + safeNearby * 8;
        final boolean admitted = stormIndex <= this.maxStormIndex;
        if (!admitted) {
            this.smoothed.incrementAndGet();
        }
        final int sliceWidth = Math.max(1, Math.min(64, this.maxStormIndex / Math.max(1, safeRadius * opWeight * 16)));
        this.lastReason = (admitted ? "admit" : "fifo-smooth") + " chunk storm op=" + (operation == null ? "unknown" : operation.name()) + " storm=" + stormIndex + " sliceWidth=" + sliceWidth + " reason=" + safe(reason);
        return new Plan(admitted, stormIndex, sliceWidth, this.lastReason);
    }

    public String statusLine() {
        return "AGCChunkStormController{tick=" + this.tickSequence
            + ", maxStormIndex=" + this.maxStormIndex
            + ", planned=" + this.planned.get()
            + ", smoothed=" + this.smoothed.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    public record Plan(boolean admitted, int stormIndex, int sliceWidth, String reason) {}
}
