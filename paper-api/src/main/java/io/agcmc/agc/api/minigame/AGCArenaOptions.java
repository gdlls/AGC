package io.agcmc.agc.api.minigame;

import org.jetbrains.annotations.NotNull;

/** Immutable options controlling AGC arena behaviour. */
public final class AGCArenaOptions {
    private final int minPlayers;
    private final int maxPlayers;
    private final boolean snapshotPlayers;
    private final boolean restorePlayersOnLeave;
    private final boolean clearInventoryOnJoin;
    private final boolean restoreLocationOnLeave;
    private final boolean allowSpectators;
    private final boolean friendlyFire;
    private final boolean isolateChat;
    private final boolean preloadChunks;
    private final AGCMinigameProfile profile;
    private final int preloadChunkRadius;
    private final boolean optimizeArenaPackets;
    private final boolean preferStableEntityTracking;
    private final boolean hideNonArenaPlayers;
    private final boolean resetOnEmpty;
    private final int playersPerShard;
    private final boolean deterministicReset;
    private final boolean readOnlyPrewarm;

    private AGCArenaOptions(final Builder builder) {
        this.minPlayers = Math.max(0, builder.minPlayers);
        this.maxPlayers = Math.max(1, builder.maxPlayers);
        this.snapshotPlayers = builder.snapshotPlayers;
        this.restorePlayersOnLeave = builder.restorePlayersOnLeave;
        this.clearInventoryOnJoin = builder.clearInventoryOnJoin;
        this.restoreLocationOnLeave = builder.restoreLocationOnLeave;
        this.allowSpectators = builder.allowSpectators;
        this.friendlyFire = builder.friendlyFire;
        this.isolateChat = builder.isolateChat;
        this.preloadChunks = builder.preloadChunks;
        this.profile = builder.profile;
        this.preloadChunkRadius = Math.max(0, builder.preloadChunkRadius);
        this.optimizeArenaPackets = builder.optimizeArenaPackets;
        this.preferStableEntityTracking = builder.preferStableEntityTracking;
        this.hideNonArenaPlayers = builder.hideNonArenaPlayers;
        this.resetOnEmpty = builder.resetOnEmpty;
        this.playersPerShard = Math.max(1, builder.playersPerShard);
        this.deterministicReset = builder.deterministicReset;
        this.readOnlyPrewarm = builder.readOnlyPrewarm;
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public int minPlayers() { return this.minPlayers; }
    public int maxPlayers() { return this.maxPlayers; }
    public boolean snapshotPlayers() { return this.snapshotPlayers; }
    public boolean restorePlayersOnLeave() { return this.restorePlayersOnLeave; }
    public boolean clearInventoryOnJoin() { return this.clearInventoryOnJoin; }
    public boolean restoreLocationOnLeave() { return this.restoreLocationOnLeave; }
    public boolean allowSpectators() { return this.allowSpectators; }
    public boolean friendlyFire() { return this.friendlyFire; }
    public boolean isolateChat() { return this.isolateChat; }
    public boolean preloadChunks() { return this.preloadChunks; }
    public @NotNull AGCMinigameProfile profile() { return this.profile; }
    public int preloadChunkRadius() { return this.preloadChunkRadius; }
    public boolean optimizeArenaPackets() { return this.optimizeArenaPackets; }
    public boolean preferStableEntityTracking() { return this.preferStableEntityTracking; }
    public boolean hideNonArenaPlayers() { return this.hideNonArenaPlayers; }
    public boolean resetOnEmpty() { return this.resetOnEmpty; }
    public int playersPerShard() { return this.playersPerShard; }
    public boolean deterministicReset() { return this.deterministicReset; }
    public boolean readOnlyPrewarm() { return this.readOnlyPrewarm; }

    public static final class Builder {
        private int minPlayers = 2;
        private int maxPlayers = 16;
        private boolean snapshotPlayers = true;
        private boolean restorePlayersOnLeave = true;
        private boolean clearInventoryOnJoin = false;
        private boolean restoreLocationOnLeave = true;
        private boolean allowSpectators = true;
        private boolean friendlyFire = false;
        private boolean isolateChat = false;
        private boolean preloadChunks = true;
        private AGCMinigameProfile profile = AGCMinigameProfile.TEAM_FIGHT;
        private int preloadChunkRadius = AGCMinigameProfile.TEAM_FIGHT.recommendedPreloadRadius();
        private boolean optimizeArenaPackets = true;
        private boolean preferStableEntityTracking = true;
        private boolean hideNonArenaPlayers = false;
        private boolean resetOnEmpty = true;
        private int playersPerShard = 64;
        private boolean deterministicReset = true;
        private boolean readOnlyPrewarm = true;

        private Builder() {
        }

        public @NotNull Builder minPlayers(final int minPlayers) { this.minPlayers = minPlayers; return this; }
        public @NotNull Builder maxPlayers(final int maxPlayers) { this.maxPlayers = maxPlayers; return this; }
        public @NotNull Builder snapshotPlayers(final boolean snapshotPlayers) { this.snapshotPlayers = snapshotPlayers; return this; }
        public @NotNull Builder restorePlayersOnLeave(final boolean restorePlayersOnLeave) { this.restorePlayersOnLeave = restorePlayersOnLeave; return this; }
        public @NotNull Builder clearInventoryOnJoin(final boolean clearInventoryOnJoin) { this.clearInventoryOnJoin = clearInventoryOnJoin; return this; }
        public @NotNull Builder restoreLocationOnLeave(final boolean restoreLocationOnLeave) { this.restoreLocationOnLeave = restoreLocationOnLeave; return this; }
        public @NotNull Builder allowSpectators(final boolean allowSpectators) { this.allowSpectators = allowSpectators; return this; }
        public @NotNull Builder friendlyFire(final boolean friendlyFire) { this.friendlyFire = friendlyFire; return this; }
        public @NotNull Builder isolateChat(final boolean isolateChat) { this.isolateChat = isolateChat; return this; }
        public @NotNull Builder preloadChunks(final boolean preloadChunks) { this.preloadChunks = preloadChunks; return this; }
        public @NotNull Builder profile(final @NotNull AGCMinigameProfile profile) {
            this.profile = profile;
            this.maxPlayers = Math.max(this.maxPlayers, profile.recommendedMaxPlayers());
            this.preloadChunkRadius = profile.recommendedPreloadRadius();
            this.optimizeArenaPackets = profile.preferAggressiveNetworkBudget();
            this.playersPerShard = Math.max(8, profile.recommendedMaxPlayers());
            this.hideNonArenaPlayers = profile == AGCMinigameProfile.DUEL || profile == AGCMinigameProfile.BATTLE_ROYALE || profile == AGCMinigameProfile.INSTANCE_WORLD;
            return this;
        }
        public @NotNull Builder preloadChunkRadius(final int preloadChunkRadius) { this.preloadChunkRadius = preloadChunkRadius; return this; }
        public @NotNull Builder optimizeArenaPackets(final boolean optimizeArenaPackets) { this.optimizeArenaPackets = optimizeArenaPackets; return this; }
        public @NotNull Builder preferStableEntityTracking(final boolean preferStableEntityTracking) { this.preferStableEntityTracking = preferStableEntityTracking; return this; }
        public @NotNull Builder hideNonArenaPlayers(final boolean hideNonArenaPlayers) { this.hideNonArenaPlayers = hideNonArenaPlayers; return this; }
        public @NotNull Builder resetOnEmpty(final boolean resetOnEmpty) { this.resetOnEmpty = resetOnEmpty; return this; }
        public @NotNull Builder playersPerShard(final int playersPerShard) { this.playersPerShard = playersPerShard; return this; }
        public @NotNull Builder deterministicReset(final boolean deterministicReset) { this.deterministicReset = deterministicReset; return this; }
        public @NotNull Builder readOnlyPrewarm(final boolean readOnlyPrewarm) { this.readOnlyPrewarm = readOnlyPrewarm; return this; }
        public @NotNull AGCArenaOptions build() { return new AGCArenaOptions(this); }
    }
}
