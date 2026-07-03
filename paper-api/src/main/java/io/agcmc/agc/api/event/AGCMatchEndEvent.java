package io.agcmc.agc.api.event;

import io.agcmc.agc.api.minigame.AGCArena;
import io.agcmc.agc.api.minigame.AGCMatchResult;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Called after an AGC arena match ends. */
public final class AGCMatchEndEvent extends AGCArenaEvent {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private final AGCMatchResult result;

    public AGCMatchEndEvent(final @NotNull AGCArena arena, final @NotNull AGCMatchResult result) {
        super(arena);
        this.result = result;
    }

    public @NotNull AGCMatchResult result() { return this.result; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLER_LIST; }
    public static @NotNull HandlerList getHandlerList() { return HANDLER_LIST; }
}
