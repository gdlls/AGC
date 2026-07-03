package io.agcmc.agc.api.event;

import io.agcmc.agc.api.minigame.AGCArena;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Called after AGC removes a player or spectator from an arena. */
public final class AGCPlayerLeaveArenaEvent extends AGCPlayerArenaEvent {
    private static final HandlerList HANDLER_LIST = new HandlerList();

    public AGCPlayerLeaveArenaEvent(final @NotNull AGCArena arena, final @NotNull Player player) {
        super(arena, player);
    }

    @Override public @NotNull HandlerList getHandlers() { return HANDLER_LIST; }
    public static @NotNull HandlerList getHandlerList() { return HANDLER_LIST; }
}
