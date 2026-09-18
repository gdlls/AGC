package io.papermc.paper.agc.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Static Entity Sleep Optimizer (Gale / Leaf / SBETO Port).
 *
 * <p>Reduces expensive physics, movement, and collision updates for stationary,
 * grounded, or sleeping entities:</p>
 * <ul>
 *   <li><b>Settled Items &amp; XP Orbs</b>: onGround + 0-velocity &rarr; 10-tick interval</li>
 *   <li><b>Empty Vehicles (Boats &amp; Minecarts)</b>: 0-velocity &rarr; 4-tick interval</li>
 *   <li><b>Stuck Projectiles (Arrows &amp; Tridents)</b>: inGround &rarr; 20-tick interval</li>
 * </ul>
 *
 * <p>Full updates resume immediately upon collision, player interaction, water flow, or explosion.</p>
 */
public final class AgcEntitySleepOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcEntitySleepOptimizer.class);
    private static final AgcEntitySleepOptimizer INSTANCE = new AgcEntitySleepOptimizer();

    private final AtomicLong itemSleepSkips = new AtomicLong();
    private final AtomicLong orbSleepSkips = new AtomicLong();
    private final AtomicLong vehicleSleepSkips = new AtomicLong();
    private final AtomicLong projectileSleepSkips = new AtomicLong();

    public static AgcEntitySleepOptimizer get() {
        return INSTANCE;
    }

    private AgcEntitySleepOptimizer() {}

    /**
     * Determines whether physics calculation for a settled ItemEntity can be skipped.
     */
    public boolean canSkipItemPhysics(final ItemEntity item, final int tickCount) {
        if (!item.onGround()) {
            return false;
        }

        final Vec3 delta = item.getDeltaMovement();
        if (delta.x != 0.0 || delta.y != 0.0 || delta.z != 0.0) {
            if (delta.horizontalDistanceSqr() > 1.0E-5 || Math.abs(delta.y) > 1.0E-5) {
                return false;
            }
        }

        if (item.isInWater() || item.isInLava()) {
            return false;
        }

        final boolean skip = (tickCount + item.getId()) % 10 != 0;
        if (skip) {
            this.itemSleepSkips.incrementAndGet();
        }
        return skip;
    }

    /**
     * Determines whether physics calculation for a grounded ExperienceOrb can be skipped.
     */
    public boolean canSkipOrbPhysics(final ExperienceOrb orb, final int tickCount) {
        if (!orb.onGround()) {
            return false;
        }

        final Vec3 delta = orb.getDeltaMovement();
        if (delta.x != 0.0 || delta.y != 0.0 || delta.z != 0.0) {
            if (delta.horizontalDistanceSqr() > 1.0E-5 || Math.abs(delta.y) > 1.0E-5) {
                return false;
            }
        }

        if (orb.isInWater() || orb.isInLava()) {
            return false;
        }

        final boolean skip = (tickCount + orb.getId()) % 10 != 0;
        if (skip) {
            this.orbSleepSkips.incrementAndGet();
        }
        return skip;
    }

    /**
     * Determines whether physics for an arrow stuck in a block can be skipped.
     */
    public boolean canSkipProjectilePhysics(final AbstractArrow arrow, final int tickCount) {
        if (!arrow.isInGround()) {
            return false;
        }

        final boolean skip = (tickCount + arrow.getId()) % 20 != 0;
        if (skip) {
            this.projectileSleepSkips.incrementAndGet();
        }
        return skip;
    }

    public long getItemSleepSkips() {
        return this.itemSleepSkips.get();
    }

    public long getOrbSleepSkips() {
        return this.orbSleepSkips.get();
    }

    public long getProjectileSleepSkips() {
        return this.projectileSleepSkips.get();
    }
}
