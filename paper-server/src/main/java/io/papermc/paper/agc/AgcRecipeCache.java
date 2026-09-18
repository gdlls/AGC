package io.papermc.paper.agc;

import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Speed Recipe Match Cache (FastWorkbench / FastFurnace port).
 *
 * <p>Caches the most recently matched {@link RecipeHolder} per {@link RecipeType}.
 * When a player crafts batches of items (e.g. shift-crafting 64 torches or planks)
 * or when furnaces/crafters process continuous items, tests the last matched recipe
 * first, avoiding the expensive stream-based iteration across the full recipe registry.</p>
 */
public final class AgcRecipeCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcRecipeCache.class);
    private static final AgcRecipeCache INSTANCE = new AgcRecipeCache();

    private final Map<RecipeType<?>, RecipeHolder<?>> lastMatchedRecipes = new ConcurrentHashMap<>();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    public static AgcRecipeCache get() {
        return INSTANCE;
    }

    private AgcRecipeCache() {}

    @SuppressWarnings("unchecked")
    public <I extends RecipeInput, T extends Recipe<I>> Optional<RecipeHolder<T>> getCachedRecipe(
        final RecipeType<T> type,
        final I input,
        final Level level
    ) {
        final RecipeHolder<?> last = this.lastMatchedRecipes.get(type);
        if (last != null) {
            try {
                final RecipeHolder<T> typed = (RecipeHolder<T>) last;
                if (typed.value().matches(input, level)) {
                    this.cacheHits.incrementAndGet();
                    return Optional.of(typed);
                }
            } catch (final ClassCastException ignored) {}
        }

        this.cacheMisses.incrementAndGet();
        return Optional.empty();
    }

    public <I extends RecipeInput, T extends Recipe<I>> void recordMatch(
        final RecipeType<T> type,
        final RecipeHolder<T> recipe
    ) {
        if (type != null && recipe != null) {
            this.lastMatchedRecipes.put(type, recipe);
        }
    }

    public void clear() {
        this.lastMatchedRecipes.clear();
    }

    public long getCacheHits() {
        return this.cacheHits.get();
    }

    public long getCacheMisses() {
        return this.cacheMisses.get();
    }
}
