package io.papermc.paper.agc.entity;

import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Density Entity Merge & Super-Stacking Engine (Clumps / ServerCore port).
 *
 * <p>Aggressively merges nearby XP orbs and dropped items to keep entity counts minimal in
 * mob grinders, automated farms, and mining explosions without loss of items or experience.</p>
 */
public final class AgcEntityMergeOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcEntityMergeOptimizer.class);
    private static final AgcEntityMergeOptimizer INSTANCE = new AgcEntityMergeOptimizer();

    private final AtomicLong xpOrbsMerged = new AtomicLong();
    private final AtomicLong itemsMerged = new AtomicLong();

    public static AgcEntityMergeOptimizer get() {
        return INSTANCE;
    }

    private AgcEntityMergeOptimizer() {}

    /**
     * Checks whether two experience orbs are eligible for merge.
     */
    public boolean tryMergeXp(final ExperienceOrb primary, final ExperienceOrb secondary) {
        if (!primary.isAlive() || !secondary.isAlive()) {
            return false;
        }

        final double dx = primary.getX() - secondary.getX();
        final double dy = primary.getY() - secondary.getY();
        final double dz = primary.getZ() - secondary.getZ();

        if (dx * dx + dy * dy + dz * dz <= 9.0) { // 3.0 blocks radius
            primary.setValue(primary.getValue() + secondary.getValue());
            secondary.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.MERGE);
            this.xpOrbsMerged.incrementAndGet();
            return true;
        }

        return false;
    }

    /**
     * Checks whether two ItemEntities can be super-merged.
     */
    public boolean tryMergeItems(final ItemEntity primary, final ItemEntity secondary) {
        if (!primary.isAlive() || !secondary.isAlive()) {
            return false;
        }

        final ItemStack primaryStack = primary.getItem();
        final ItemStack secondaryStack = secondary.getItem();

        if (ItemStack.isSameItemSameComponents(primaryStack, secondaryStack)) {
            final int combined = primaryStack.getCount() + secondaryStack.getCount();
            if (combined <= primaryStack.getMaxStackSize()) { // Preserves vanilla max stack size limit
                primaryStack.setCount(combined);
                primary.setItem(primaryStack);
                secondary.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.MERGE);
                this.itemsMerged.incrementAndGet();
                return true;
            }
        }

        return false;
    }

    public void recordXpMerge() {
        this.xpOrbsMerged.incrementAndGet();
    }

    public void recordItemMerge() {
        this.itemsMerged.incrementAndGet();
    }

    public long getXpOrbsMerged() {
        return this.xpOrbsMerged.get();
    }

    public long getItemsMerged() {
        return this.itemsMerged.get();
    }
}
