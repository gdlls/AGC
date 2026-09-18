package io.papermc.paper.agc.profiling;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AGC — Real-Time Entity Counting & Spatial Hotspot Density Profiler.
 *
 * <p>Aggregates live entity counts per-world, per-chunk, and per-entity-type.
 * Applies a weighted computational cost model to calculate entity load rankings
 * and pinpoints dense entity clusters (mob farms, villager breeding halls, item drops).</p>
 */
public final class AgcEntityCounter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcEntityCounter.class);
    private static final AgcEntityCounter INSTANCE = new AgcEntityCounter();

    public static AgcEntityCounter get() {
        return INSTANCE;
    }

    private AgcEntityCounter() {}

    public record EntityTypeStats(
        EntityType type,
        int count,
        double weight,
        double totalWeightedLoad
    ) {}

    public record ChunkHotspot(
        String worldName,
        int chunkX,
        int chunkZ,
        int totalEntities,
        double weightedLoad,
        Map<EntityType, Integer> topTypes
    ) {}

    public record WorldEntityStats(
        String worldName,
        int totalEntities,
        int loadedChunks,
        double totalWeightedLoad,
        int playerCount
    ) {}

    /**
     * Determines the estimated relative CPU tick weight for a given entity type.
     */
    public static double getEntityWeight(final EntityType type) {
        if (type == null) return 1.0;
        return switch (type) {
            case VILLAGER, WANDERING_TRADER, PILLAGER, EVOKER, VINDICATOR, ILLUSIONER, WITCH, RAVAGER -> 4.5;
            case ZOMBIE, SKELETON, CREEPER, SPIDER, ENDERMAN, DROWNED, HUSK, STRAY, PIGLIN, PIGLIN_BRUTE, ZOMBIFIED_PIGLIN, WITHER_SKELETON, BLAZE, GHAST, PHANTOM, WARDEN, WITHER, ENDER_DRAGON -> 2.5;
            case COW, SHEEP, PIG, CHICKEN, HORSE, DONKEY, MULE, LLAMA, FOX, WOLF, CAT, PARROT, BEE, GOAT, FROG, CAMEL, SNIFFER, AXOLOTL, IRON_GOLEM, SNOW_GOLEM -> 1.5;
            case ITEM, ARROW, SPECTRAL_ARROW, TRIDENT, SNOWBALL, EGG, SPLASH_POTION, LINGERING_POTION, EXPERIENCE_BOTTLE, EYE_OF_ENDER, ENDER_PEARL, FIREWORK_ROCKET, WIND_CHARGE, BREEZE_WIND_CHARGE -> 0.4;
            case EXPERIENCE_ORB -> 0.3;
            case ARMOR_STAND, ITEM_FRAME, GLOW_ITEM_FRAME, PAINTING, LEASH_KNOT, BLOCK_DISPLAY, ITEM_DISPLAY, TEXT_DISPLAY, INTERACTION -> 0.1;
            case MARKER, AREA_EFFECT_CLOUD -> 0.05;
            default -> 1.0;
        };
    }

    /**
     * Generates a complete live entity census across all loaded worlds.
     */
    public List<EntityTypeStats> getTopEntities(final int limit) {
        final EnumMap<EntityType, Integer> counts = new EnumMap<>(EntityType.class);

        for (final World world : Bukkit.getWorlds()) {
            for (final Entity entity : world.getEntities()) {
                final EntityType t = entity.getType();
                counts.merge(t, 1, Integer::sum);
            }
        }

        final List<EntityTypeStats> list = new ArrayList<>(counts.size());
        for (final Map.Entry<EntityType, Integer> entry : counts.entrySet()) {
            final EntityType type = entry.getKey();
            final int count = entry.getValue();
            final double weight = getEntityWeight(type);
            list.add(new EntityTypeStats(type, count, weight, count * weight));
        }

        list.sort(Comparator.comparingDouble(EntityTypeStats::totalWeightedLoad).reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    /**
     * Identifies top hotspot chunks with dense entity populations.
     */
    public List<ChunkHotspot> getTopHotspotChunks(final int limit) {
        final List<ChunkHotspot> hotspots = new ArrayList<>();

        for (final World world : Bukkit.getWorlds()) {
            final String worldName = world.getName();
            for (final Chunk chunk : world.getLoadedChunks()) {
                final Entity[] entities = chunk.getEntities();
                if (entities == null || entities.length == 0) continue;

                final int count = entities.length;
                double load = 0.0;
                final EnumMap<EntityType, Integer> typeCounts = new EnumMap<>(EntityType.class);

                for (final Entity e : entities) {
                    final EntityType t = e.getType();
                    typeCounts.merge(t, 1, Integer::sum);
                    load += getEntityWeight(t);
                }

                hotspots.add(new ChunkHotspot(worldName, chunk.getX(), chunk.getZ(), count, load, typeCounts));
            }
        }

        hotspots.sort(Comparator.comparingDouble(ChunkHotspot::weightedLoad).reversed());
        return hotspots.subList(0, Math.min(limit, hotspots.size()));
    }

    /**
     * Computes entity statistics grouped per world.
     */
    public List<WorldEntityStats> getWorldBreakdown() {
        final List<WorldEntityStats> list = new ArrayList<>();

        for (final World world : Bukkit.getWorlds()) {
            final String name = world.getName();
            final List<Entity> entities = world.getEntities();
            final int count = entities.size();
            double load = 0.0;
            for (final Entity e : entities) {
                load += getEntityWeight(e.getType());
            }

            list.add(new WorldEntityStats(name, count, world.getLoadedChunks().length, load, world.getPlayerCount()));
        }

        list.sort(Comparator.comparingDouble(WorldEntityStats::totalWeightedLoad).reversed());
        return list;
    }

    /**
     * Generates human-readable report.
     */
    public String getReport() {
        final List<EntityTypeStats> topEntities = getTopEntities(15);
        final List<ChunkHotspot> topChunks = getTopHotspotChunks(8);
        final List<WorldEntityStats> worlds = getWorldBreakdown();

        final StringBuilder sb = new StringBuilder(2048);
        sb.append("=== AGC Real-Time Entity & Hotspot Census ===\n");

        sb.append("--- Worlds Breakdown ---\n");
        sb.append(String.format("%-22s %8s %12s %10s %8s\n", "World", "Entities", "WeightedLoad", "Chunks", "Players"));
        for (final WorldEntityStats w : worlds) {
            sb.append(String.format("%-22s %8d %12.1f %10d %8d\n",
                w.worldName(), w.totalEntities(), w.totalWeightedLoad(), w.loadedChunks(), w.playerCount()));
        }

        sb.append("\n--- Top 15 Entity Types (by Weighted Load) ---\n");
        sb.append(String.format("%-24s %8s %8s %12s\n", "Entity Type", "Count", "Weight", "WeightedLoad"));
        for (final EntityTypeStats e : topEntities) {
            sb.append(String.format("%-24s %8d %8.2f %12.1f\n",
                e.type().name(), e.count(), e.weight(), e.totalWeightedLoad()));
        }

        sb.append("\n--- Top Dense Hotspot Chunks ---\n");
        sb.append(String.format("%-20s %12s %8s %12s %s\n", "World", "Chunk(X,Z)", "Entities", "WeightedLoad", "Top Types"));
        for (final ChunkHotspot c : topChunks) {
            final StringBuilder typesSb = new StringBuilder();
            c.topTypes().entrySet().stream()
                .sorted(Map.Entry.<EntityType, Integer>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> typesSb.append(e.getKey().name()).append(":").append(e.getValue()).append(" "));

            sb.append(String.format("%-20s [%4d, %4d] %8d %12.1f %s\n",
                c.worldName(), c.chunkX(), c.chunkZ(), c.totalEntities(), c.weightedLoad(), typesSb));
        }
        sb.append("=============================================");
        return sb.toString();
    }
}
