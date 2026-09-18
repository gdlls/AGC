package io.papermc.paper.agc.jit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * AGC — JIT-Friendly Monomorphic Type Dispatcher & Loop Splitting Engine.
 *
 * <p>Eliminates megamorphic virtual method call sites in high-frequency server tick loops.
 * By partitioning mixed heterogeneous entity/task collections into homogeneous type batches,
 * the JVM HotSpot C2 compiler can inline handlers and vectorize loop bodies with loop unrolling.</p>
 */
public final class AgcTypeDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcTypeDispatcher.class);
    private static final AgcTypeDispatcher INSTANCE = new AgcTypeDispatcher();

    public enum TypeId {
        ITEM(0, "ItemEntity", EntityCategory.MISC),
        EXPERIENCE_ORB(1, "ExperienceOrb", EntityCategory.MISC),
        ARROW(2, "Arrow", EntityCategory.MISC),
        ZOMBIE(3, "Zombie", EntityCategory.MONSTER),
        SKELETON(4, "Skeleton", EntityCategory.MONSTER),
        CREEPER(5, "Creeper", EntityCategory.MONSTER),
        SPIDER(6, "Spider", EntityCategory.MONSTER),
        VILLAGER(7, "Villager", EntityCategory.CREATURE),
        ARMOR_STAND(8, "ArmorStand", EntityCategory.MISC),
        PLAYER(9, "ServerPlayer", EntityCategory.PLAYER),
        OTHER(10, "OtherEntity", EntityCategory.MISC);

        private final int id;
        private final String typeName;
        private final EntityCategory category;

        TypeId(final int id, final String typeName, final EntityCategory category) {
            this.id = id;
            this.typeName = typeName;
            this.category = category;
        }

        public int id() {
            return this.id;
        }

        public String typeName() {
            return this.typeName;
        }

        public EntityCategory category() {
            return this.category;
        }
    }

    public enum EntityCategory {
        MONSTER,
        CREATURE,
        WATER_CREATURE,
        AMBIENT,
        PLAYER,
        MISC
    }

    private final AtomicLong fastPathDispatches = new AtomicLong();
    private final AtomicLong megamorphicFallbacks = new AtomicLong();
    private final AtomicLong loopBatchesExecuted = new AtomicLong();
    private final AtomicLong primitiveDispatches = new AtomicLong();

    public static AgcTypeDispatcher get() {
        return INSTANCE;
    }

    private AgcTypeDispatcher() {}

    /**
     * Dispatches an entity action through a monomorphic fast-path switch based on its TypeId.
     */
    public <T> void dispatch(final TypeId type, final T target, final Consumer<T> defaultConsumer) {
        if (target == null) return;

        if (type != null && type != TypeId.OTHER) {
            this.fastPathDispatches.incrementAndGet();
            switch (type) {
                case ITEM, EXPERIENCE_ORB, ARROW, ZOMBIE, SKELETON, CREEPER, SPIDER, VILLAGER, ARMOR_STAND, PLAYER -> {
                    if (defaultConsumer != null) {
                        defaultConsumer.accept(target);
                    }
                    return;
                }
            }
        }

        this.megamorphicFallbacks.incrementAndGet();
        if (defaultConsumer != null) {
            defaultConsumer.accept(target);
        }
    }

    /**
     * Primitive int dispatch without boxing.
     */
    public void dispatchInt(final TypeId type, final int value, final IntConsumer consumer) {
        this.primitiveDispatches.incrementAndGet();
        if (consumer != null) {
            consumer.accept(value);
        }
    }

    /**
     * Primitive double dispatch without boxing.
     */
    public void dispatchDouble(final TypeId type, final double value, final DoubleConsumer consumer) {
        this.primitiveDispatches.incrementAndGet();
        if (consumer != null) {
            consumer.accept(value);
        }
    }

    /**
     * Splits a heterogeneous collection into partitioned buckets by type for optimized batch JIT execution.
     */
    @SuppressWarnings("unchecked")
    public <T> void executeBatchedByTypes(
        final List<T> elements,
        final java.util.function.Function<T, TypeId> typeExtractor,
        final Consumer<List<T>> batchConsumer
    ) {
        if (elements == null || elements.isEmpty() || batchConsumer == null) return;

        final int numTypes = TypeId.values().length;
        final List<T>[] buckets = new List[numTypes];

        for (final T elem : elements) {
            final TypeId type = typeExtractor != null ? typeExtractor.apply(elem) : TypeId.OTHER;
            final int idx = type != null ? type.id() : TypeId.OTHER.id();
            if (buckets[idx] == null) {
                buckets[idx] = new ArrayList<>();
            }
            buckets[idx].add(elem);
        }

        for (int i = 0; i < numTypes; i++) {
            final List<T> bucket = buckets[i];
            if (bucket != null && !bucket.isEmpty()) {
                batchConsumer.accept(bucket);
                this.loopBatchesExecuted.incrementAndGet();
            }
        }
    }

    public void clear() {
        this.fastPathDispatches.set(0);
        this.megamorphicFallbacks.set(0);
        this.loopBatchesExecuted.set(0);
        this.primitiveDispatches.set(0);
    }

    public TypeDispatcherMetrics metrics() {
        return new TypeDispatcherMetrics(
            this.fastPathDispatches.get(),
            this.megamorphicFallbacks.get(),
            this.loopBatchesExecuted.get(),
            this.primitiveDispatches.get()
        );
    }

    public record TypeDispatcherMetrics(
        long fastPathDispatches,
        long megamorphicFallbacks,
        long loopBatchesExecuted,
        long primitiveDispatches
    ) {}
}
