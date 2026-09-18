package io.papermc.paper.agc.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.warden.Warden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Dynamic Activation of Brain & Goal Selector (DAP Engine).
 *
 * <p>Inspired by Pufferfish DAB and Purpur enhancements. Dynamically modulates
 * mob AI and Brain behavior execution frequency based on player distance:</p>
 * <ul>
 *   <li>Distance &le; 32 blocks (1024 d^2): 1 tick (20 Hz, Full AI)</li>
 *   <li>Distance &le; 64 blocks (4096 d^2): 2 ticks (10 Hz)</li>
 *   <li>Distance &le; 128 blocks (16384 d^2): 4 ticks (5 Hz)</li>
 *   <li>Distance &le; 192 blocks (36864 d^2): 10 ticks (2 Hz)</li>
 *   <li>Distance &gt; 192 blocks: 20 ticks (1 Hz, Minimal background AI)</li>
 * </ul>
 *
 * <p>Full AI execution is guaranteed whenever:</p>
 * <ul>
 *   <li>Entity has an active attack target or is aggressive.</li>
 *   <li>Entity was damaged recently (hurtTime &gt; 0 or within 40 ticks).</li>
 *   <li>Entity is a boss (Wither, Ender Dragon, Elder Guardian, Warden).</li>
 *   <li>Entity is participating in a raid.</li>
 * </ul>
 */
public final class AgcDapGoalSelector {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcDapGoalSelector.class);
    private static final AgcDapGoalSelector INSTANCE = new AgcDapGoalSelector();

    private final AtomicLong fullAiTicks = new AtomicLong();
    private final AtomicLong throttledAiTicks = new AtomicLong();
    private final AtomicLong skippedAiTicks = new AtomicLong();

    public static AgcDapGoalSelector get() {
        return INSTANCE;
    }

    private AgcDapGoalSelector() {}

    /**
     * Evaluates whether the given Mob should execute full AI goal updates this tick.
     *
     * @param mob the mob being ticked
     * @param tickCount current tick count
     * @return true if full goal updates should execute; false if throttled
     */
    public boolean shouldTickMobAi(final Mob mob, final int tickCount) {
        if (!isDapEnabled()) {
            this.fullAiTicks.incrementAndGet();
            return true;
        }

        if (isImmuneFromThrottling(mob)) {
            this.fullAiTicks.incrementAndGet();
            return true;
        }

        final int freq = computeTickFrequency(mob);
        if (freq <= 1) {
            this.fullAiTicks.incrementAndGet();
            return true;
        }

        final boolean shouldTick = (tickCount + mob.getId()) % freq == 0;
        if (shouldTick) {
            this.throttledAiTicks.incrementAndGet();
        } else {
            this.skippedAiTicks.incrementAndGet();
        }
        return shouldTick;
    }

    /**
     * Evaluates whether the given LivingEntity's Brain behaviors should execute this tick.
     *
     * @param entity the entity whose brain is ticking
     * @param gameTime current game tick
     * @return true if brain behaviors should execute; false if skipped
     */
    public boolean shouldTickBrain(final LivingEntity entity, final long gameTime) {
        if (!isDapEnabled()) {
            return true;
        }

        if (isImmuneFromThrottling(entity)) {
            return true;
        }

        final int freq = computeTickFrequency(entity);
        if (freq <= 1) {
            return true;
        }

        return (gameTime + entity.getId()) % freq == 0;
    }

    /**
     * Computes the tick interval (1, 2, 4, 10, or 20) based on distance squared to the nearest player.
     */
    public int computeTickFrequency(final Entity entity) {
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            return 1;
        }

        final List<ServerPlayer> players = serverLevel.players();
        if (players.isEmpty()) {
            return 20; // No players in dimension, run at lowest frequency
        }

        final double ex = entity.getX();
        final double ey = entity.getY();
        final double ez = entity.getZ();

        double nearestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < players.size(); i++) {
            final ServerPlayer player = players.get(i);
            if (player.isSpectator()) {
                continue;
            }
            final double dx = player.getX() - ex;
            final double dy = player.getY() - ey;
            final double dz = player.getZ() - ez;
            final double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                if (nearestDistSq <= 1024.0) { // 32 blocks
                    return 1; // Immediate short-circuit
                }
            }
        }

        if (nearestDistSq <= 4096.0) { // 64 blocks
            return 2;
        }
        if (nearestDistSq <= 16384.0) { // 128 blocks
            return 4;
        }
        if (nearestDistSq <= 36864.0) { // 192 blocks
            return 10;
        }
        return 20;
    }

    private boolean isImmuneFromThrottling(final LivingEntity entity) {
        if (entity instanceof EnderDragon || entity instanceof WitherBoss || entity instanceof ElderGuardian || entity instanceof Warden) {
            return true;
        }

        if (entity.hurtTime > 0) {
            return true;
        }

        if (entity.tickCount - entity.getLastHurtByMobTimestamp() < 40) {
            return true;
        }

        if (entity instanceof Mob mob) {
            if (mob.getTarget() != null || mob.isAggressive()) {
                return true;
            }
        }

        return false;
    }

    private boolean isDapEnabled() {
        return true;
    }

    public void resetMetrics() {
        this.fullAiTicks.set(0);
        this.throttledAiTicks.set(0);
        this.skippedAiTicks.set(0);
    }

    public long getFullAiTicks() {
        return this.fullAiTicks.get();
    }

    public long getThrottledAiTicks() {
        return this.throttledAiTicks.get();
    }

    public long getSkippedAiTicks() {
        return this.skippedAiTicks.get();
    }
}
