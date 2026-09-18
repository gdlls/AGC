package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Performance Native KBSync & Singleplayer-Feel Combat Engine.
 *
 * <p>Predicts client-side ground state and compensates knockback trajectory based on
 * latency and kinematic solvers. Defaults to {@code false} for strict vanilla parity.</p>
 */
public final class AgcSingleplayerFeelCombatEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcSingleplayerFeelCombatEngine.class);
    private static final AgcSingleplayerFeelCombatEngine INSTANCE = new AgcSingleplayerFeelCombatEngine();

    public static AgcSingleplayerFeelCombatEngine getInstance() {
        return INSTANCE;
    }

    private final AtomicBoolean enabledOverride = new AtomicBoolean(false);
    private volatile boolean explicitOverrideSet = false;
    private volatile boolean offGroundSyncEnabled = true;

    private final Map<UUID, PlayerCombatState> playerStates = new ConcurrentHashMap<>();

    private final AtomicLong totalCombatHitsProcessed = new AtomicLong(0);
    private final AtomicLong subTickPacketsDispatched = new AtomicLong(0);
    private final AtomicLong groundCompensationsApplied = new AtomicLong(0);
    private final AtomicLong offGroundCompensationsApplied = new AtomicLong(0);
    private final AtomicLong microFlushesTriggered = new AtomicLong(0);
    private final AtomicLong totalDispatchLatencyNanos = new AtomicLong(0);

    public static final double DEFAULT_GRAVITY = 0.08;
    public static final double BASE_VERTICAL_VELOCITY_SPRINT = 0.40;
    public static final double BASE_VERTICAL_VELOCITY_NORMAL = 0.36080000519752503;
    public static final double PING_OFFSET_MS = 25.0;
    public static final double SPIKE_THRESHOLD_MS = 100.0;
    public static final long DUPLICATE_VELOCITY_WINDOW_NANOS = 45_000_000L;
    public static final long DAMAGE_TO_VELOCITY_WINDOW_NANOS = 100_000_000L;

    public AgcSingleplayerFeelCombatEngine() {}

    public boolean isEnabled() {
        if (explicitOverrideSet) {
            return enabledOverride.get();
        }
        return AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.SINGLEPLAYER_FEEL_COMBAT);
    }

    public void setEnabled(final boolean enabled) {
        this.enabledOverride.set(enabled);
        this.explicitOverrideSet = true;
    }

    public void clearOverride() {
        this.explicitOverrideSet = false;
    }

    public boolean isOffGroundSyncEnabled() {
        return offGroundSyncEnabled;
    }

    public void setOffGroundSyncEnabled(final boolean enabled) {
        this.offGroundSyncEnabled = enabled;
    }

    // Per-Player Combat State

    public static final class PlayerCombatState {
        private final UUID playerId;
        private volatile double lastKnownPingMs = 20.0;
        private volatile double previousPingMs = 20.0;
        private volatile double avgPingMs = 20.0;
        private volatile double jitterMs = 0.0;
        private volatile long lastDamageTimeNanos = 0;
        private volatile long lastVelocityDispatchNanos = 0;
        private volatile double lastDispatchedVy = 0.0;
        private volatile int lastDamageTicks = 0;
        private volatile boolean projectileDamage = false;
        private volatile double customVerticalVelocity = BASE_VERTICAL_VELOCITY_NORMAL;

        public PlayerCombatState(final UUID playerId) {
            this.playerId = playerId;
        }

        public UUID getPlayerId() { return playerId; }
        public double getLastKnownPingMs() { return lastKnownPingMs; }
        public double getJitterMs() { return jitterMs; }

        public void setPing(final double pingMs) {
            final double clamped = Math.max(1.0, Math.min(1000.0, pingMs));
            this.previousPingMs = this.lastKnownPingMs;
            this.lastKnownPingMs = clamped;
            this.jitterMs = Math.abs(clamped - this.avgPingMs);
            this.avgPingMs = (this.avgPingMs * 0.8) + (clamped * 0.2);
        }

        public double getCompensatedPingMs() {
            if (lastKnownPingMs - previousPingMs > SPIKE_THRESHOLD_MS) {
                return Math.max(1.0, previousPingMs - PING_OFFSET_MS);
            }
            return Math.max(1.0, lastKnownPingMs - PING_OFFSET_MS);
        }

        public int getCompensatedTicks() {
            return (int) Math.ceil(getCompensatedPingMs() * 20.0 / 1000.0);
        }

        public int getTicks() {
            return (int) Math.ceil(lastKnownPingMs * 20.0 / 1000.0);
        }

        public long getLastDamageTimeNanos() { return lastDamageTimeNanos; }
        public void markDamageTime() { this.lastDamageTimeNanos = System.nanoTime(); }

        public boolean hasFreshPlayerDamage() {
            final long elapsed = System.nanoTime() - lastDamageTimeNanos;
            return elapsed >= 0 && elapsed < DAMAGE_TO_VELOCITY_WINDOW_NANOS;
        }

        public long getLastVelocityDispatchNanos() { return lastVelocityDispatchNanos; }
        public void setLastVelocityDispatchNanos(final long nanos) { this.lastVelocityDispatchNanos = nanos; }
        public double getLastDispatchedVy() { return lastDispatchedVy; }
        public void setLastDispatchedVy(final double vy) { this.lastDispatchedVy = vy; }
        public int getLastDamageTicks() { return lastDamageTicks; }
        public void setLastDamageTicks(final int ticks) { this.lastDamageTicks = ticks; }
        public boolean isProjectileDamage() { return projectileDamage; }
        public void setProjectileDamage(final boolean projectileDamage) { this.projectileDamage = projectileDamage; }
        public double getCustomVerticalVelocity() { return customVerticalVelocity; }
        public void setCustomVerticalVelocity(final double vy) { this.customVerticalVelocity = vy; }
    }

    public PlayerCombatState getOrCreateState(final UUID playerId) {
        return playerStates.computeIfAbsent(playerId, PlayerCombatState::new);
    }

    public void removePlayer(final UUID playerId) {
        playerStates.remove(playerId);
    }

    public void clear() {
        playerStates.clear();
        totalCombatHitsProcessed.set(0);
        subTickPacketsDispatched.set(0);
        groundCompensationsApplied.set(0);
        offGroundCompensationsApplied.set(0);
        microFlushesTriggered.set(0);
        totalDispatchLatencyNanos.set(0);
    }

    /**
     * Immutable 3D vector record.
     */
    public record CombatVector3(double x, double y, double z) {
        public CombatVector3 withY(final double newY) {
            return new CombatVector3(this.x, newY, this.z);
        }
        public double lengthSq() {
            return x * x + y * y + z * z;
        }
    }


    /**
     * Calculates latency-compensated knockback trajectory for the victim.
     */
    public CombatVector3 calculateKnockbackTrajectory(
        final PlayerCombatState state,
        final CombatVector3 originalVelocity,
        final double distanceToGround,
        final boolean attackerSprinting,
        final double attackerCooldown,
        final int knockbackLevel,
        final double knockbackResistance
    ) {
        if (!isEnabled()) {
            return originalVelocity;
        }

        totalCombatHitsProcessed.incrementAndGet();

        if (distanceToGround <= 0.0) {
            return originalVelocity;
        }

        final double vy = originalVelocity.y();
        final int compTicks = state.getCompensatedTicks();

        final boolean clientOnGround = isClientPredictedOnGround(vy, distanceToGround, compTicks);

        if (clientOnGround) {
            if (state.getLastDamageTicks() > 8) {
                return originalVelocity;
            }

            double targetVy = BASE_VERTICAL_VELOCITY_NORMAL;
            if (attackerCooldown > 0.848 || knockbackLevel > 0) {
                targetVy = BASE_VERTICAL_VELOCITY_SPRINT;
            } else if (!attackerSprinting) {
                final double resistanceFactor = 0.04000000119 * knockbackResistance * 10.0;
                targetVy = Math.max(0.0, BASE_VERTICAL_VELOCITY_NORMAL - resistanceFactor);
            }

            if (knockbackLevel > 0) {
                targetVy = BASE_VERTICAL_VELOCITY_SPRINT;
            }

            groundCompensationsApplied.incrementAndGet();
            return originalVelocity.withY(targetVy);
        } else if (offGroundSyncEnabled) {
            final double compensatedOffGroundVy = calculateCompensatedOffGroundVelocity(vy, DEFAULT_GRAVITY, state.getTicks());
            offGroundCompensationsApplied.incrementAndGet();
            return originalVelocity.withY(compensatedOffGroundVy);
        }

        return originalVelocity;
    }

    /**
     * Backward compatibility overload for bridge.
     */
    public CombatVector3 calculateKnockbackTrajectory(
        final PlayerCombatState victimState,
        final PlayerCombatState attackerState,
        final CombatVector3 originalVelocity,
        final double distanceToGround,
        final boolean attackerSprinting,
        final double attackerCooldown,
        final int knockbackLevel,
        final double knockbackResistance
    ) {
        return calculateKnockbackTrajectory(
            victimState, originalVelocity, distanceToGround, attackerSprinting, attackerCooldown, knockbackLevel, knockbackResistance
        );
    }


    /**
     * Determines if the player is on the ground clientside, but not serverside.
     * Returns {@code (tMax + tFall) - compTicks <= 0 && distanceToGround <= 1.3}.
     */
    public static boolean isClientPredictedOnGround(final double verticalVelocity, final double distanceToGround, final int compTicks) {
        if (distanceToGround > 1.3) {
            return false;
        }

        final int tMax = verticalVelocity > 0 ? calculateTimeToMaxVelocity(verticalVelocity, DEFAULT_GRAVITY) : 0;
        if (tMax == -1) {
            return false;
        }

        final double maxElevation = verticalVelocity > 0 ? calculateDistanceTraveled(verticalVelocity, tMax, DEFAULT_GRAVITY) : 0.0;
        final int tFall = calculateFallTime(verticalVelocity, maxElevation + distanceToGround, DEFAULT_GRAVITY);
        if (tFall == -1) {
            return false;
        }

        return (tMax + tFall) - compTicks <= 0;
    }

    /**
     * Gets the compensated off-ground velocity across latency ticks.
     */
    public static double calculateCompensatedOffGroundVelocity(double velocity, double gravity, int ticks) {
        int t = Math.min(30, Math.max(0, ticks));
        while (t > 0) {
            velocity -= gravity;
            velocity *= 0.98;
            t--;
        }
        return velocity;
    }

    public static int calculateTimeToMaxVelocity(final double velocity, final double gravity) {
        if (gravity <= 0.0 || velocity <= 0.0) return -1;
        double curVel = velocity;
        int ticks = 0;
        while (curVel > 0.0) {
            if (ticks > 30) return -1;
            curVel -= gravity;
            curVel = Math.min(curVel, 3.92);
            curVel *= 0.98;
            ticks++;
        }
        return ticks;
    }

    public static double calculateDistanceTraveled(final double velocity, final int time, final double gravity) {
        double totalDist = 0.0;
        double curVel = velocity;
        for (int i = 0; i < time; i++) {
            totalDist += curVel;
            curVel = ((curVel - gravity) * 0.98);
            curVel = Math.min(curVel, 3.92);
        }
        return totalDist;
    }

    public static int calculateFallTime(final double initialVelocity, final double distance, final double gravity) {
        if (gravity <= 0.0 || distance <= 0.0) return -1;
        double velocity = Math.abs(initialVelocity);
        double remainingDist = distance;
        int ticks = 0;
        while (remainingDist > 0.0) {
            if (ticks > 30) return -1;
            velocity += gravity;
            velocity = Math.min(velocity, 3.92);
            velocity *= 0.98;
            remainingDist -= velocity;
            ticks++;
        }
        return ticks;
    }


    /**
     * Sub-tick motion dispatch pipeline.
     *
     * @param state player combat state
     * @param velocity velocity vector
     * @param packetDispatcher packet dispatch callback
     * @return true if dispatched immediately
     */
    public boolean dispatchSubTickKnockback(
        final PlayerCombatState state,
        final CombatVector3 velocity,
        final Runnable packetDispatcher
    ) {
        if (!isEnabled()) {
            return false;
        }

        final long nowNanos = System.nanoTime();

        // Anti-ghosting: suppress duplicate velocity packets within window
        if (nowNanos - state.getLastVelocityDispatchNanos() < DUPLICATE_VELOCITY_WINDOW_NANOS
            && Math.abs(velocity.y() - state.getLastDispatchedVy()) < 0.001) {
            return false;
        }

        state.setLastVelocityDispatchNanos(nowNanos);
        state.setLastDispatchedVy(velocity.y());

        if (packetDispatcher != null) {
            packetDispatcher.run();
        }

        subTickPacketsDispatched.incrementAndGet();
        microFlushesTriggered.incrementAndGet();

        final long latencyNanos = System.nanoTime() - nowNanos;
        totalDispatchLatencyNanos.addAndGet(latencyNanos);

        return true;
    }


    public long getTotalCombatHitsProcessed() { return totalCombatHitsProcessed.get(); }
    public long getSubTickPacketsDispatched() { return subTickPacketsDispatched.get(); }
    public long getGroundCompensationsApplied() { return groundCompensationsApplied.get(); }
    public long getOffGroundCompensationsApplied() { return offGroundCompensationsApplied.get(); }
    public long getMicroFlushesTriggered() { return microFlushesTriggered.get(); }
    public double getAverageDispatchLatencyMicros() {
        final long count = subTickPacketsDispatched.get();
        if (count == 0) return 0.0;
        return (totalDispatchLatencyNanos.get() / (double) count) / 1000.0;
    }

    public String generateReport() {
        return String.format(
            "=== AGC Native KBSync & Singleplayer-Feel Combat Report ===\n" +
            "  Engine Active             : %b\n" +
            "  Off-Ground Sync           : %b\n" +
            "  Total Hits Processed      : %d\n" +
            "  Sub-Tick Packets Sent     : %d\n" +
            "  Ground Compensations (KB) : %d\n" +
            "  Off-Ground Compensations  : %d\n" +
            "  Micro-Flushes Triggered   : %d\n" +
            "  Avg Dispatch Latency      : %.3f µs\n" +
            "===========================================================",
            isEnabled(),
            isOffGroundSyncEnabled(),
            getTotalCombatHitsProcessed(),
            getSubTickPacketsDispatched(),
            getGroundCompensationsApplied(),
            getOffGroundCompensationsApplied(),
            getMicroFlushesTriggered(),
            getAverageDispatchLatencyMicros()
        );
    }
}

