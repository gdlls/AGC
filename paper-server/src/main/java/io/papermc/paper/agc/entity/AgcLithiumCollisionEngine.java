package io.papermc.paper.agc.entity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Lithium-Grade Entity Collision & Push Fast Predicates.
 *
 * <p>Ports Lithium's {@code entity.collisions} family as zero-allocation pure
 * predicates evaluated <i>before</i> expensive world lookups:</p>
 * <ul>
 *   <li><b>Fluid-push skip</b> ({@code collisions.fluid}): when the chunk sections
 *       overlapped by the entity contain no fluid of the pushed type, the push scan
 *       is skipped entirely. Callers pass the precomputed section flag.</li>
 *   <li><b>Suffocation fast path</b> ({@code collisions.suffocation}): stream-free
 *       eye-block check; empty collision shape short-circuits without streams.</li>
 *   <li><b>Unpushable cramming skip</b> ({@code collisions.unpushable_cramming}):
 *       entities that cannot be pushed neither push nor accumulate cramming.</li>
 *   <li><b>Projectile-projectile skip</b> ({@code projectile_projectile_collisions}):
 *       huge ender-pearl stasis stacks stop doing O(n^2) pearl-vs-pearl checks for
 *       type pairs that can never collide.</li>
 *   <li><b>Cramming early termination</b>: count loop exits as soon as the damage
 *       threshold is proven, instead of scanning the whole crowd.</li>
 * </ul>
 *
 * <p>All decisions are behavior-identical to vanilla: every skip corresponds to a
 * case where vanilla's full computation provably yields "no effect".</p>
 */
public final class AgcLithiumCollisionEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcLithiumCollisionEngine.class);
    private static final AgcLithiumCollisionEngine INSTANCE = new AgcLithiumCollisionEngine();

    private final AtomicLong fluidPushSkips = new AtomicLong();
    private final AtomicLong suffocationFastPaths = new AtomicLong();
    private final AtomicLong unpushableSkips = new AtomicLong();
    private final AtomicLong projectilePairSkips = new AtomicLong();
    private final AtomicLong crammingEarlyTerminations = new AtomicLong();
    private final AtomicLong pushPairEvaluations = new AtomicLong();

    public static AgcLithiumCollisionEngine get() {
        return INSTANCE;
    }

    private AgcLithiumCollisionEngine() {}

    /**
     * Fluid-push skip: {@code true} means "do not scan for fluid push".
     *
     * @param sectionsContainFluid precomputed: any overlapped section contains the fluid type
     * @param entityTouchesFluid entity AABB actually intersects fluid (cheap pre-test by caller)
     */
    public boolean shouldSkipFluidPush(final boolean sectionsContainFluid, final boolean entityTouchesFluid) {
        this.pushPairEvaluations.incrementAndGet();
        if (!sectionsContainFluid || !entityTouchesFluid) {
            this.fluidPushSkips.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Suffocation fast path: {@code true} means "definitely not suffocating, skip
     * the stream-based shape scan".
     *
     * @param eyeBlockHasCollisionShape whether the eye block has any collision shape
     * @param eyeBlockShapeEmpty whether that shape is empty (no suffocation possible)
     */
    public boolean isDefinitelyNotSuffocating(final boolean eyeBlockHasCollisionShape, final boolean eyeBlockShapeEmpty) {
        if (!eyeBlockHasCollisionShape || eyeBlockShapeEmpty) {
            this.suffocationFastPaths.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Push-pair skip for {@code Entity.push}: {@code true} means the pair can never
     * affect each other so the push math (sqrt/div) can be skipped.
     *
     * @param eitherNoPhysics either entity has {@code noPhysics}
     * @param sameVehicle passengers of the same vehicle
     * @param neitherPushable neither entity is pushable
     * @param onlyPlayersCollide Paper {@code collisions.onlyPlayersCollide} active
     * @param involvesPlayer either entity is a player
     */
    public boolean shouldSkipPushPair(
        final boolean eitherNoPhysics,
        final boolean sameVehicle,
        final boolean neitherPushable,
        final boolean onlyPlayersCollide,
        final boolean involvesPlayer
    ) {
        this.pushPairEvaluations.incrementAndGet();
        if (eitherNoPhysics || sameVehicle || neitherPushable
            || (onlyPlayersCollide && !involvesPlayer)) {
            this.unpushableSkips.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Projectile pair skip: pairs of projectile type names that never collide.
     * Vanilla collision requires overlapping hitboxes AND type-specific rules;
     * ender pearls, snowballs, eggs and fishing hooks never collide with their own
     * kind, so stasis chambers with 1000+ stacked pearls skip the O(n^2)checks.
     */
    public boolean shouldSkipProjectilePair(final String typeA, final String typeB) {
        this.pushPairEvaluations.incrementAndGet();
        if (typeA == null || typeB == null) {
            return false;
        }
        if (typeA.equals(typeB) && isNonSelfCollidingProjectile(typeA)) {
            this.projectilePairSkips.incrementAndGet();
            return true;
        }
        return false;
    }

    private static boolean isNonSelfCollidingProjectile(final String type) {
        return switch (type) {
            case "minecraft:ender_pearl", "minecraft:snowball", "minecraft:egg",
                 "minecraft:fishing_bobber", "minecraft:experience_bottle",
                 "minecraft:splash_potion", "minecraft:lingering_potion" -> true;
            default -> false;
        };
    }

    /**
     * Cramming counter with early termination: counts non-passenger entities but
     * stops as soon as {@code maxCramming} is exceeded (damage threshold proven).
     *
     * @param passengerFlags parallel to iteration order: {@code true} = is passenger (excluded)
     * @param count number of entries to consider
     * @param maxCramming gamerule value ({@code <= 0} disables)
     * @return {@code true} if cramming damage applies
     */
    public boolean crammingDamageApplies(final boolean[] passengerFlags, final int count, final int maxCramming) {
        if (maxCramming <= 0 || passengerFlags == null || count <= 0) {
            return false;
        }
        final int threshold = maxCramming - 1;
        final int limit = Math.min(count, passengerFlags.length);
        int found = 0;
        for (int i = 0; i < limit; i++) {
            if (!passengerFlags[i] && ++found > threshold) {
                this.crammingEarlyTerminations.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    /**
     * NMS direct hook for {@code LivingEntity.pushEntities}: vanilla-identical
     * cramming check with early termination and identical RNG consumption.
     *
     * <p>Short-circuits BEFORE the quarter roll exactly like vanilla's
     * {@code &&} chain ({@code size <= max-1} consumes no RNG), then rolls once
     * via {@code quarterRoll} and counts non-passengers with early exit. Damage
     * outcome and RNG state are bit-identical to the vanilla block it replaces.</p>
     *
     * @param pushable pushable entities in range (vanilla query result, unmodified)
     * @param maxCramming {@code MAX_ENTITY_CRAMMING} gamerule value
     * @param quarterRoll supplies {@code random.nextInt(4)} (evaluated at most once)
     * @return {@code true} if cramming damage must be applied
     */
    public void recordCrammingEarlyTermination() {
        this.crammingEarlyTerminations.incrementAndGet();
    }

    public void recordUnpushableSkip() {
        this.unpushableSkips.incrementAndGet();
    }

    public boolean shouldApplyCrammingDamage(
        final java.util.List<net.minecraft.world.entity.Entity> pushable,
        final int maxCramming,
        final java.util.function.IntSupplier quarterRoll
    ) {
        if (maxCramming <= 0 || pushable == null || pushable.size() <= maxCramming - 1) {
            return false;
        }
        if (quarterRoll == null || quarterRoll.getAsInt() != 0) {
            return false;
        }
        final int threshold = maxCramming - 1;
        int found = 0;
        for (int i = 0, n = pushable.size(); i < n; i++) {
            if (!pushable.get(i).isPassenger() && ++found > threshold) {
                this.crammingEarlyTerminations.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    /**
     * NMS direct hook for same-class projectile pairs (stasis-chamber O(n^2) killer).
     * Callers pre-filter with {@code instanceof Projectile} on both sides so the
     * common non-projectile path pays only two type checks. Only pairs vanilla
     * provably never collides (ender pearls never hit ender pearls) return true.
     *
     * @param clazz exact runtime class shared by both projectiles
     */
    public boolean shouldSkipSameClassProjectilePair(final Class<?> clazz) {
        this.pushPairEvaluations.incrementAndGet();
        if (clazz == null || !NON_SELF_COLLIDING_PROJECTILE_CLASSES.contains(clazz.getName())) {
            return false;
        }
        this.projectilePairSkips.incrementAndGet();
        return true;
    }

    private static final java.util.Set<String> NON_SELF_COLLIDING_PROJECTILE_CLASSES = java.util.Set.of(
        "net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl",
        "net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball",
        "net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg",
        "net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle",
        "net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownPotion"
    );

    public void clear() {
        this.fluidPushSkips.set(0);
        this.suffocationFastPaths.set(0);
        this.unpushableSkips.set(0);
        this.projectilePairSkips.set(0);
        this.crammingEarlyTerminations.set(0);
        this.pushPairEvaluations.set(0);
    }

    public CollisionMetrics metrics() {
        return new CollisionMetrics(
            this.fluidPushSkips.get(),
            this.suffocationFastPaths.get(),
            this.unpushableSkips.get(),
            this.projectilePairSkips.get(),
            this.crammingEarlyTerminations.get(),
            this.pushPairEvaluations.get()
        );
    }

    public record CollisionMetrics(
        long fluidPushSkips,
        long suffocationFastPaths,
        long unpushableSkips,
        long projectilePairSkips,
        long crammingEarlyTerminations,
        long pushPairEvaluations
    ) {}
}
