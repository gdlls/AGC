package io.agcmc.agc.api.event;

import io.agcmc.agc.api.minigame.AGCArena;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Called before AGC adds a player to an arena. */
public final class AGCPlayerJoinArenaEvent extends AGCPlayerArenaEvent implements Cancellable {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private boolean cancelled;

    public AGCPlayerJoinArenaEvent(final @NotNull AGCArena arena, final @NotNull Player player) {
        super(arena, player);
    }

    @Override public boolean isCancelled() { return this.cancelled; }
    @Override public void setCancelled(final boolean cancel) { this.cancelled = cancel; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLER_LIST; }
    public static @NotNull HandlerList getHandlerList() { return HANDLER_LIST; }
}
