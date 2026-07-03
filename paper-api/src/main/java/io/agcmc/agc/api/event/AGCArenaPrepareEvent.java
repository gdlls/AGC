package io.agcmc.agc.api.event;

import io.agcmc.agc.api.minigame.AGCArena;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Called before an AGC arena is prepared for a match or warmup. */
public final class AGCArenaPrepareEvent extends AGCArenaEvent implements Cancellable {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private boolean cancelled;

    public AGCArenaPrepareEvent(final @NotNull AGCArena arena) {
        super(arena);
    }

    @Override public boolean isCancelled() { return this.cancelled; }
    @Override public void setCancelled(final boolean cancel) { this.cancelled = cancel; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLER_LIST; }
    public static @NotNull HandlerList getHandlerList() { return HANDLER_LIST; }
}
