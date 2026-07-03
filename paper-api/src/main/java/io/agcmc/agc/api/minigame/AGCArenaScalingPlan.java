package io.agcmc.agc.api.minigame;

import org.jetbrains.annotations.NotNull;

/** Immutable arena scaling hint for large mini-game networks. */
public final class AGCArenaScalingPlan {
    private final AGCMinigameProfile profile;
    private final int expectedPlayers;
    private final int recommendedShards;
    private final int playersPerShard;
    private final int preloadChunkRadius;
    private final boolean isolateVisibility;
    private final boolean useReadOnlyPrepare;

    public AGCArenaScalingPlan(
        final @NotNull AGCMinigameProfile profile,
        final int expectedPlayers,
        final int playersPerShard,
        final int preloadChunkRadius,
        final boolean isolateVisibility,
        final boolean useReadOnlyPrepare
    ) {
        this.profile = profile;
        this.expectedPlayers = Math.max(0, expectedPlayers);
        this.playersPerShard = Math.max(1, playersPerShard);
        this.recommendedShards = Math.max(1, (this.expectedPlayers + this.playersPerShard - 1) / this.playersPerShard);
        this.preloadChunkRadius = Math.max(0, preloadChunkRadius);
        this.isolateVisibility = isolateVisibility;
        this.useReadOnlyPrepare = useReadOnlyPrepare;
    }

    public @NotNull AGCMinigameProfile profile() { return this.profile; }
    public int expectedPlayers() { return this.expectedPlayers; }
    public int recommendedShards() { return this.recommendedShards; }
    public int playersPerShard() { return this.playersPerShard; }
    public int preloadChunkRadius() { return this.preloadChunkRadius; }
    public boolean isolateVisibility() { return this.isolateVisibility; }
    public boolean useReadOnlyPrepare() { return this.useReadOnlyPrepare; }

    @Override
    public String toString() {
        return "AGCArenaScalingPlan{"
            + "profile=" + this.profile
            + ", expectedPlayers=" + this.expectedPlayers
            + ", recommendedShards=" + this.recommendedShards
            + ", playersPerShard=" + this.playersPerShard
            + ", preloadChunkRadius=" + this.preloadChunkRadius
            + ", isolateVisibility=" + this.isolateVisibility
            + ", useReadOnlyPrepare=" + this.useReadOnlyPrepare
            + '}';
    }
}
