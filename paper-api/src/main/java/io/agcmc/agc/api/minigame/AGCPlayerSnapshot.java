package io.agcmc.agc.api.minigame;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Captures enough player state for mini-game join/leave restoration. */
public final class AGCPlayerSnapshot {
    private final Location location;
    private final GameMode gameMode;
    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final boolean allowFlight;
    private final boolean flying;

    private AGCPlayerSnapshot(final @NotNull Player player) {
        this.location = player.getLocation().clone();
        this.gameMode = player.getGameMode();
        this.contents = cloneArray(player.getInventory().getContents());
        this.armor = cloneArray(player.getInventory().getArmorContents());
        this.health = player.getHealth();
        this.foodLevel = player.getFoodLevel();
        this.saturation = player.getSaturation();
        this.allowFlight = player.getAllowFlight();
        this.flying = player.isFlying();
    }

    public static @NotNull AGCPlayerSnapshot capture(final @NotNull Player player) {
        return new AGCPlayerSnapshot(player);
    }

    public void restore(final @NotNull Player player, final boolean restoreLocation) {
        player.getInventory().setContents(cloneArray(this.contents));
        player.getInventory().setArmorContents(cloneArray(this.armor));
        player.setGameMode(this.gameMode);
        try {
            player.setHealth(Math.max(0.1D, Math.min(this.health, 20.0D)));
        } catch (final RuntimeException ignored) {
            // Another plugin may have changed max health; do not fail restoration.
        }
        player.setFoodLevel(this.foodLevel);
        player.setSaturation(this.saturation);
        player.setAllowFlight(this.allowFlight);
        if (this.allowFlight) {
            player.setFlying(this.flying);
        }
        if (restoreLocation) {
            player.teleport(this.location.clone());
        }
        player.updateInventory();
    }

    public @NotNull Location location() { return this.location.clone(); }
    public @NotNull GameMode gameMode() { return this.gameMode; }

    private static ItemStack[] cloneArray(final @Nullable ItemStack[] source) {
        if (source == null) {
            return new ItemStack[0];
        }
        final ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; ++i) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }
}
