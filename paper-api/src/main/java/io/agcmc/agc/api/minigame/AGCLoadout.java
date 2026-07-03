package io.agcmc.agc.api.minigame;

import java.util.Arrays;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Immutable mini-game loadout/kit helper. */
public final class AGCLoadout {
    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final boolean clearFirst;

    private AGCLoadout(final Builder builder) {
        this.contents = cloneArray(builder.contents);
        this.armor = cloneArray(builder.armor);
        this.clearFirst = builder.clearFirst;
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public void apply(final @NotNull Player player) {
        if (this.clearFirst) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
        }
        if (this.contents.length > 0) {
            player.getInventory().setContents(cloneArray(this.contents));
        }
        if (this.armor.length > 0) {
            player.getInventory().setArmorContents(cloneArray(this.armor));
        }
        player.updateInventory();
    }

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

    public static final class Builder {
        private ItemStack[] contents = new ItemStack[0];
        private ItemStack[] armor = new ItemStack[0];
        private boolean clearFirst = true;

        private Builder() {
        }

        public @NotNull Builder contents(final @Nullable ItemStack... contents) {
            this.contents = contents == null ? new ItemStack[0] : Arrays.copyOf(contents, contents.length);
            return this;
        }

        public @NotNull Builder armor(final @Nullable ItemStack... armor) {
            this.armor = armor == null ? new ItemStack[0] : Arrays.copyOf(armor, armor.length);
            return this;
        }

        public @NotNull Builder clearFirst(final boolean clearFirst) {
            this.clearFirst = clearFirst;
            return this;
        }

        public @NotNull AGCLoadout build() {
            return new AGCLoadout(this);
        }
    }
}
