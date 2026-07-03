package io.agcmc.agc.api.event;

import io.agcmc.agc.api.minigame.AGCArena;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Called after an AGC arena has been reset to its lobby phase. */
public final class AGCArenaResetEvent extends AGCArenaEvent {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private final String reason;

    public AGCArenaResetEvent(final @NotNull AGCArena arena, final @NotNull String reason) {
        super(arena);
        this.reason = reason;
    }

    public @NotNull String reason() { return this.reason; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLER_LIST; }
    public static @NotNull HandlerList getHandlerList() { return HANDLER_LIST; }
}
