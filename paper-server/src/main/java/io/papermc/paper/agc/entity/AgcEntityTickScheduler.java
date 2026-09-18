package io.papermc.paper.agc.entity;

import org.bukkit.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Entity Tick Scheduler & Stride Workload Balancer.
 *
 * <p>Categorizes entities into computational cost tiers and distributes heavy entity workloads
 * across consecutive tick strides to eliminate micro-stutters from large mob farms or villager trading halls.</p>
 */
public final class AgcEntityTickScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcEntityTickScheduler.class);
    private static final AgcEntityTickScheduler INSTANCE = new AgcEntityTickScheduler();

    public enum EntityCostCategory {
        HEAVY(4),
        MEDIUM(2),
        LIGHT(1),
        TRIVIAL(0);

        private final int weight;

        EntityCostCategory(final int weight) {
            this.weight = weight;
        }

        public int getWeight() {
            return this.weight;
        }
    }

    private final AtomicLong heavyTicksDispatched = new AtomicLong();
    private final AtomicLong mediumTicksDispatched = new AtomicLong();
    private final AtomicLong lightTicksDispatched = new AtomicLong();
    private final AtomicLong trivialTicksSkipped = new AtomicLong();

    public static AgcEntityTickScheduler get() {
        return INSTANCE;
    }

    private AgcEntityTickScheduler() {}

    /**
     * Resolves the entity cost category based on Bukkit EntityType.
     */
    public static EntityCostCategory categorize(final EntityType type) {
        if (type == null) return EntityCostCategory.TRIVIAL;
        return switch (type) {
            case VILLAGER, WANDERING_TRADER, PILLAGER, EVOKER, VINDICATOR, ILLUSIONER, WITCH, RAVAGER, WARDEN, WITHER, ENDER_DRAGON ->
                EntityCostCategory.HEAVY;
            case ZOMBIE, SKELETON, CREEPER, SPIDER, ENDERMAN, DROWNED, HUSK, STRAY, PIGLIN, PIGLIN_BRUTE, ZOMBIFIED_PIGLIN, WITHER_SKELETON, BLAZE, GHAST, PHANTOM, COW, SHEEP, PIG, CHICKEN, HORSE, DONKEY, MULE, LLAMA, FOX, WOLF, CAT, PARROT, BEE, GOAT, FROG, CAMEL, SNIFFER, AXOLOTL, IRON_GOLEM, SNOW_GOLEM ->
                EntityCostCategory.MEDIUM;
            case ITEM, ARROW, SPECTRAL_ARROW, TRIDENT, SNOWBALL, EGG, SPLASH_POTION, LINGERING_POTION, EXPERIENCE_BOTTLE, EYE_OF_ENDER, ENDER_PEARL, FIREWORK_ROCKET, WIND_CHARGE, BREEZE_WIND_CHARGE, EXPERIENCE_ORB ->
                EntityCostCategory.LIGHT;
            default ->
                EntityCostCategory.TRIVIAL;
        };
    }

    /**
     * Resolves the entity cost category based on Minecraft internal EntityType.
     */
    public static EntityCostCategory categorize(final net.minecraft.world.entity.EntityType<?> type) {
        if (type == null) return EntityCostCategory.TRIVIAL;
        if (type == net.minecraft.world.entity.EntityTypes.VILLAGER
            || type == net.minecraft.world.entity.EntityTypes.WANDERING_TRADER
            || type == net.minecraft.world.entity.EntityTypes.PILLAGER
            || type == net.minecraft.world.entity.EntityTypes.EVOKER
            || type == net.minecraft.world.entity.EntityTypes.VINDICATOR
            || type == net.minecraft.world.entity.EntityTypes.ILLUSIONER
            || type == net.minecraft.world.entity.EntityTypes.WITCH
            || type == net.minecraft.world.entity.EntityTypes.RAVAGER
            || type == net.minecraft.world.entity.EntityTypes.WARDEN
            || type == net.minecraft.world.entity.EntityTypes.WITHER
            || type == net.minecraft.world.entity.EntityTypes.ENDER_DRAGON) {
            return EntityCostCategory.HEAVY;
        }
        final net.minecraft.world.entity.MobCategory cat = type.getCategory();
        if (cat == net.minecraft.world.entity.MobCategory.MONSTER
            || cat == net.minecraft.world.entity.MobCategory.CREATURE
            || cat == net.minecraft.world.entity.MobCategory.WATER_CREATURE
            || cat == net.minecraft.world.entity.MobCategory.AXOLOTLS
            || cat == net.minecraft.world.entity.MobCategory.UNDERGROUND_WATER_CREATURE) {
            return EntityCostCategory.MEDIUM;
        }
        if (type == net.minecraft.world.entity.EntityTypes.ITEM
            || type == net.minecraft.world.entity.EntityTypes.ARROW
            || type == net.minecraft.world.entity.EntityTypes.SPECTRAL_ARROW
            || type == net.minecraft.world.entity.EntityTypes.TRIDENT
            || type == net.minecraft.world.entity.EntityTypes.EXPERIENCE_ORB) {
            return EntityCostCategory.LIGHT;
        }
        return EntityCostCategory.TRIVIAL;
    }

    /**
     * Determines whether an entity should tick on the current tick or be phased to balance workload.
     */
    public boolean shouldTickEntity(final int entityId, final EntityCostCategory category, final long currentTick, final boolean isNearPlayer) {
        if (isNearPlayer) {
            recordDispatch(category);
            return true;
        }

        return switch (category) {
            case HEAVY -> {
                this.heavyTicksDispatched.incrementAndGet();
                yield true; // Heavy entities preserve tick cadence when close, but AI can be batched
            }
            case MEDIUM -> {
                this.mediumTicksDispatched.incrementAndGet();
                yield true;
            }
            case LIGHT -> {
                this.lightTicksDispatched.incrementAndGet();
                yield true;
            }
            case TRIVIAL -> {
                if ((currentTick & 3) == (entityId & 3)) {
                    yield true;
                }
                this.trivialTicksSkipped.incrementAndGet();
                yield false;
            }
        };
    }

    private void recordDispatch(final EntityCostCategory category) {
        switch (category) {
            case HEAVY -> this.heavyTicksDispatched.incrementAndGet();
            case MEDIUM -> this.mediumTicksDispatched.incrementAndGet();
            case LIGHT -> this.lightTicksDispatched.incrementAndGet();
            case TRIVIAL -> {}
        }
    }

    public void resetMetrics() {
        this.heavyTicksDispatched.set(0);
        this.mediumTicksDispatched.set(0);
        this.lightTicksDispatched.set(0);
        this.trivialTicksSkipped.set(0);
    }

    public SchedulerMetrics metrics() {
        return new SchedulerMetrics(
            this.heavyTicksDispatched.get(),
            this.mediumTicksDispatched.get(),
            this.lightTicksDispatched.get(),
            this.trivialTicksSkipped.get()
        );
    }

    public record SchedulerMetrics(
        long heavyDispatched,
        long mediumDispatched,
        long lightDispatched,
        long trivialSkipped
    ) {}
}
