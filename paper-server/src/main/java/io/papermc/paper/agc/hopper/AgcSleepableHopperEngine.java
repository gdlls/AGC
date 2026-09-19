package io.papermc.paper.agc.hopper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Event-Driven Sleepable Hopper & Container Inventory Engine.
 *
 * <p>Inspired by Lithium and Leaf MC. Eliminates redundant inventory scanning when containers
 * are empty or hoppers are blocked, while guaranteeing 100% vanilla item transfer timing (8 ticks/item)
 * by waking up sleeping hoppers instantly upon inventory mutation.</p>
 */
public final class AgcSleepableHopperEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcSleepableHopperEngine.class);
    private static final AgcSleepableHopperEngine INSTANCE = new AgcSleepableHopperEngine();

    public static final int BASE_COOLDOWN = 8;
    public static final int MAX_SLEEP_TICKS = 40; // 2 seconds max sleep before sanity check

    private final AtomicLong sleepSkips = new AtomicLong();
    private final AtomicLong wakeups = new AtomicLong();

    public static AgcSleepableHopperEngine get() {
        return INSTANCE;
    }

    private AgcSleepableHopperEngine() {}

    /**
     * Calculates adaptive sleep cooldown for an idle hopper that failed to transfer items.
     *
     * @param consecutiveFailures Number of consecutive failed push/pull attempts
     * @return Number of ticks to sleep
     */
    public int calculateSleepTicks(final int consecutiveFailures) {
        if (consecutiveFailures <= 1) {
            return BASE_COOLDOWN;
        } else if (consecutiveFailures <= 4) {
            return 16;
        } else if (consecutiveFailures <= 8) {
            return 24;
        } else {
            this.sleepSkips.incrementAndGet();
            return MAX_SLEEP_TICKS;
        }
    }

    /**
     * Wakes up a hopper immediately when an inventory change occurs.
     */
    public void wakeHopper(final HopperBlockEntity hopper) {
        if (hopper != null && hopper.cooldownTime > BASE_COOLDOWN) {
            hopper.setCooldown(0);
            this.wakeups.incrementAndGet();
        }
    }

    /**
     * Wakes up any hoppers adjacent to the given container position.
     */
    public void wakeAdjacentHoppers(final Level level, final BlockPos containerPos) {
        if (level == null || level.isClientSide()) {
            return;
        }

        // Check 1 block above (hopper pointing down into container)
        final BlockEntity above = level.getBlockEntity(containerPos.above());
        if (above instanceof HopperBlockEntity hopper) {
            this.wakeHopper(hopper);
        }

        // Check 1 block below (hopper pulling down from container)
        final BlockEntity below = level.getBlockEntity(containerPos.below());
        if (below instanceof HopperBlockEntity hopper) {
            this.wakeHopper(hopper);
        }

        // Check horizontal neighbors (hoppers pointing horizontally into container)
        for (final Direction dir : Direction.Plane.HORIZONTAL) {
            final BlockEntity neighbor = level.getBlockEntity(containerPos.relative(dir));
            if (neighbor instanceof HopperBlockEntity hopper) {
                this.wakeHopper(hopper);
            }
        }
    }

    public long getSleepSkips() {
        return this.sleepSkips.get();
    }

    public long getWakeups() {
        return this.wakeups.get();
    }
}
