package io.agcmc.agc.api.minigame;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/** Axis-aligned world bounds for lightweight arena containment checks. */
public final class AGCArenaBounds {
    private final UUID worldId;
    private final String worldName;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public AGCArenaBounds(final @NotNull World world, final int x1, final int y1, final int z1, final int x2, final int y2, final int z2) {
        this.worldId = world.getUID();
        this.worldName = world.getName();
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public boolean contains(final @NotNull Location location) {
        final World world = location.getWorld();
        if (world == null || !Objects.equals(this.worldId, world.getUID())) {
            return false;
        }
        final int x = location.getBlockX();
        final int y = location.getBlockY();
        final int z = location.getBlockZ();
        return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
    }

    public @NotNull UUID worldId() { return this.worldId; }
    public @NotNull String worldName() { return this.worldName; }
    public int minX() { return this.minX; }
    public int minY() { return this.minY; }
    public int minZ() { return this.minZ; }
    public int maxX() { return this.maxX; }
    public int maxY() { return this.maxY; }
    public int maxZ() { return this.maxZ; }
}
