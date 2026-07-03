package io.agcmc.agc.api.minigame;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Simple team container for arena mini-games. */
public final class AGCTeam {
    private final String id;
    private Component displayName;
    private final LinkedHashSet<UUID> players = new LinkedHashSet<>();

    public AGCTeam(final @NotNull String id, final @NotNull Component displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public @NotNull String id() { return this.id; }
    public @NotNull Component displayName() { return this.displayName; }
    public void displayName(final @NotNull Component displayName) { this.displayName = displayName; }

    public synchronized boolean add(final @NotNull Player player) {
        return this.players.add(player.getUniqueId());
    }

    public synchronized boolean remove(final @NotNull Player player) {
        return this.players.remove(player.getUniqueId());
    }

    public synchronized boolean contains(final @NotNull Player player) {
        return this.players.contains(player.getUniqueId());
    }

    public synchronized @NotNull Set<UUID> playerIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.players));
    }

    public synchronized int size() {
        return this.players.size();
    }

    public synchronized void clear() {
        this.players.clear();
    }
}
