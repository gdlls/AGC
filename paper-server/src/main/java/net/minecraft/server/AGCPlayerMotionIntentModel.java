package net.minecraft.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local/player-local movement intent model for chunk/entity lookahead.
 * It predicts only read-only planning cells; it never teleports players, changes
 * physics, adjusts view distance or commits chunk operations out of order.
 */
public final class AGCPlayerMotionIntentModel {
    public static final AGCPlayerMotionIntentModel INSTANCE = new AGCPlayerMotionIntentModel();

    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();
    private final AtomicLong observed = new AtomicLong();
    private final AtomicLong predicted = new AtomicLong();
    private final AtomicLong budgetWaits = new AtomicLong();
    private volatile long tickSequence;
    private volatile int horizonTicks = 2;
    private volatile int cellSizeBlocks = 32;

    private AGCPlayerMotionIntentModel() {
    }

    public void configure(final int horizonTicks, final int cellSizeBlocks) {
        this.horizonTicks = Math.max(1, Math.min(6, horizonTicks));
        this.cellSizeBlocks = Math.max(8, Math.min(128, cellSizeBlocks));
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        if (this.samples.size() > 200_000) {
            this.samples.clear();
        }
    }

    public Intent observe(final UUID playerId, final String worldKey, final double x, final double z, final double dx, final double dz, final boolean sprinting, final boolean elytra) {
        final UUID key = playerId == null ? new UUID(0L, 0L) : playerId;
        this.observed.incrementAndGet();
        final double speedMultiplier = elytra ? 2.5D : sprinting ? 1.35D : 1.0D;
        final double predictedX = x + dx * this.horizonTicks * speedMultiplier;
        final double predictedZ = z + dz * this.horizonTicks * speedMultiplier;
        final int cellX = floorDiv((int) Math.floor(predictedX), this.cellSizeBlocks);
        final int cellZ = floorDiv((int) Math.floor(predictedZ), this.cellSizeBlocks);
        final Intent intent = new Intent(key, worldKey == null ? "minecraft:overworld" : worldKey, predictedX, predictedZ, cellX, cellZ, Math.hypot(dx, dz), this.horizonTicks, this.tickSequence);
        this.samples.put(key, new Sample(x, z, dx, dz, intent));
        this.predicted.incrementAndGet();
        return intent;
    }

    public Intent planStatic(final UUID playerId, final String reason) {
        final UUID key = playerId == null ? new UUID(0L, 0L) : playerId;
        final Sample last = this.samples.get(key);
        final long cost = last == null ? 2L : Math.max(1L, Math.round(1.0D + last.intent().speed() * 16.0D));
        final AGCScale16ControlLaw.Admission admission = AGCScale16ControlLaw.INSTANCE.claim(AGCScale16ControlLaw.Axis.PLAYER_MOTION, cost, reason);
        if (!admission.admitted()) {
            this.budgetWaits.incrementAndGet();
            return last == null ? new Intent(key, "unknown", 0.0D, 0.0D, 0, 0, 0.0D, 0, this.tickSequence) : last.intent();
        }
        this.predicted.incrementAndGet();
        return last == null ? new Intent(key, "unknown", 0.0D, 0.0D, 0, 0, 0.0D, this.horizonTicks, this.tickSequence) : last.intent();
    }

    public String statusLine() {
        return "AGCPlayerMotionIntentModel{tick=" + this.tickSequence
            + ", samples=" + this.samples.size()
            + ", horizonTicks=" + this.horizonTicks
            + ", cellSize=" + this.cellSizeBlocks
            + ", observed=" + this.observed.get()
            + ", predicted=" + this.predicted.get()
            + ", budgetWaits=" + this.budgetWaits.get()
            + '}';
    }

    private static int floorDiv(final int value, final int divisor) {
        return Math.floorDiv(value, Math.max(1, divisor));
    }

    private record Sample(double x, double z, double dx, double dz, Intent intent) {
    }

    public record Intent(UUID playerId, String worldKey, double predictedX, double predictedZ, int cellX, int cellZ, double speed, int horizonTicks, long tickSequence) {
    }
}
