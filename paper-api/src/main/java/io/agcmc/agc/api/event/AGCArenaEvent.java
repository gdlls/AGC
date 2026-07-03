package io.agcmc.agc.api.event;

import io.agcmc.agc.api.minigame.AGCArena;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;

/** Base event for AGC mini-game arenas. */
public abstract class AGCArenaEvent extends Event {
    private final AGCArena arena;

    protected AGCArenaEvent(final @NotNull AGCArena arena) {
        super(false);
        this.arena = arena;
    }

    public @NotNull AGCArena arena() {
        return this.arena;
    }
}
