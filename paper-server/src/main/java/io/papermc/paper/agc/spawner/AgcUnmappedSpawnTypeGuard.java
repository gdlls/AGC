package io.papermc.paper.agc.spawner;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import org.bukkit.craftbukkit.CraftRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AGC — crash guard for natural-spawn candidates the Bukkit API cannot name.
 *
 * <h2>The crash this exists for (observed 2026-09-13, twice, on a live server)</h2>
 * <pre>
 * net.minecraft.ReportedException: Exception ticking world
 *   at net.minecraft.world.level.NaturalSpawner.isValidSpawnPostitionForType(NaturalSpawner.java:412)
 * Caused by: java.lang.IllegalArgumentException
 *   at org.bukkit.craftbukkit.entity.CraftEntityType.minecraftToBukkit(CraftEntityType.java:24)
 *   at net.minecraft.world.level.NaturalSpawner.isValidSpawnPostitionForType(NaturalSpawner.java:412)
 * </pre>
 * <p>Paper constructs a {@code PreCreatureSpawnEvent} for every natural spawn candidate, and that
 * constructor translates the NMS {@code EntityType} into its Bukkit counterpart. On this tree the
 * translation throws for {@code minecraft:sulfur_cube}: the mob exists in the 26.2 snapshot registries
 * and appears in the bundled biome spawn lists, but this tree's {@code org.bukkit.entity.EntityType}
 * enum has no entry for it (no {@code SulfurCube} API interface and no {@code CraftSulfurCube} mapping
 * either). Any random spawn roll that picks it therefore takes the whole server down mid-tick — a rare,
 * seed- and biome-dependent crash that no benchmark run can be trusted around.</p>
 *
 * <h2>What the guard does, and why skipping is the right call</h2>
 * <p>A type the Bukkit API cannot name cannot be delivered to a plugin, added to the entity registry
 * under a Bukkit type, or represented in {@code CreatureSpawnEvent} — so the only two options are
 * "crash" or "do not spawn this candidate". {@link #isUnrepresentable} reports the latter, and the
 * spawner treats it exactly like an event-cancelled spawn: the candidate is skipped and the category
 * loop continues. Retiring the guard is a one-liner once the API gains the mob (add
 * {@code SULFUR_CUBE} to {@code EntityType} with its interface + Craft mapping, delete the call site in
 * {@code NaturalSpawner}, re-run the spawn benchmark).</p>
 *
 * <p>Results are cached per entity-type key and the warning is logged once per key, so the hot spawner
 * path pays one map lookup after the first encounter. Static state only; no configuration, no gate —
 * a crash fix must not depend on an operator opting in.</p>
 */
public final class AgcUnmappedSpawnTypeGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcUnmappedSpawnTypeGuard.class);

    /** type key -> whether the Bukkit registry can name it (mixed positively and negatively). */
    private static final Map<ResourceKey<EntityType<?>>, Boolean> REPRESENTABLE = new ConcurrentHashMap<>();
    private static final Set<ResourceKey<EntityType<?>>> WARNED = ConcurrentHashMap.newKeySet();

    private AgcUnmappedSpawnTypeGuard() {}

    /**
     * @param type the NMS entity type a spawn candidate wants to create
     * @return {@code true} when the Bukkit {@code Registry.ENTITY_TYPE} has no entry for it, i.e. the
     *         candidate must be skipped instead of translated (which would throw)
     */
    public static boolean isUnrepresentable(final EntityType<?> type) {
        if (type == null) {
            return true;
        }
        try {
            final ResourceKey<EntityType<?>> key = CraftRegistry.getMinecraftRegistry(Registries.ENTITY_TYPE)
                .getResourceKey(type)
                .orElse(null);
            if (key == null) {
                // Not registered in the NMS registry at all: vanilla cannot translate it either, and
                // CraftEntityType.minecraftToBukkit would throw on the same path.
                return true;
            }
            final Boolean cached = REPRESENTABLE.get(key);
            if (cached != null) {
                return !cached;
            }
            final boolean representable = lookup(key) != null;
            REPRESENTABLE.put(key, representable);
            if (!representable && WARNED.add(key)) {
                LOGGER.warn("[AGC] Entity type {} has no org.bukkit.entity.EntityType mapping in this build;"
                    + " natural spawn candidates of this type are skipped so the spawner cannot crash the"
                    + " server (see AgcUnmappedSpawnTypeGuard). Remove this guard once the API gains the mob.",
                    key.identifier());
            }
            return !representable;
        } catch (final Throwable t) {
            // Fail open: never suppress spawning because the guard itself could not read the registries
            // (for example outside a bootstrapped server). The vanilla path is preserved.
            return false;
        }
    }

    /** Mirrors {@code CraftEntityType.minecraftToBukkit}'s registry lookup, without throwing. */
    private static org.bukkit.entity.EntityType lookup(final ResourceKey<EntityType<?>> key) {
        try {
            return org.bukkit.Registry.ENTITY_TYPE.get(
                org.bukkit.craftbukkit.util.CraftNamespacedKey.fromMinecraft(key.identifier()));
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Test/telemetry hook: drops the memoised lookups (the warn-once state is kept). */
    public static void clearCache() {
        REPRESENTABLE.clear();
    }

    /** Test hook: number of entity-type keys currently memoised. */
    public static int cachedTypes() {
        return REPRESENTABLE.size();
    }
}
