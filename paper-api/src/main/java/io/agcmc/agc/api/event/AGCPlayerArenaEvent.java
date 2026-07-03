package io.agcmc.agc.api.event;

import io.agcmc.agc.api.minigame.AGCArena;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Base event for an AGC arena event involving a player. */
public abstract class AGCPlayerArenaEvent extends AGCArenaEvent {
    private final Player player;

    protected AGCPlayerArenaEvent(final @NotNull AGCArena arena, final @NotNull Player player) {
        super(arena);
        this.player = player;
    }

    public @NotNull Player player() {
        return this.player;
    }
}
