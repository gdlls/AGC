package io.agcmc.agc.api.minigame;

import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Result emitted when an AGC arena match ends. */
public final class AGCMatchResult {
    private final String reason;
    private final String winningTeamId;
    private final Duration duration;

    public AGCMatchResult(final @NotNull String reason, final @Nullable String winningTeamId, final @NotNull Duration duration) {
        this.reason = reason;
        this.winningTeamId = winningTeamId;
        this.duration = duration;
    }

    public @NotNull String reason() { return this.reason; }
    public @Nullable String winningTeamId() { return this.winningTeamId; }
    public @NotNull Duration duration() { return this.duration; }
}
