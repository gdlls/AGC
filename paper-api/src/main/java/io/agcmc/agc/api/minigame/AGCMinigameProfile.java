package io.agcmc.agc.api.minigame;

/**
 * Pre-tuned mini-game profile hints.
 * <p>
 * These values are API-level hints for arena plugins. AGC keeps Bukkit/Paper
 * semantics; profiles are not permission to skip gameplay ticks or plugin
 * callbacks.
 */
public enum AGCMinigameProfile {
    LOBBY(128, 2, true),
    DUEL(2, 1, true),
    TEAM_FIGHT(32, 2, true),
    BATTLE_ROYALE(256, 4, true),
    INSTANCE_WORLD(64, 3, true),
    PVE_ARENA(48, 2, false);

    private final int recommendedMaxPlayers;
    private final int recommendedPreloadRadius;
    private final boolean preferAggressiveNetworkBudget;

    AGCMinigameProfile(final int recommendedMaxPlayers, final int recommendedPreloadRadius, final boolean preferAggressiveNetworkBudget) {
        this.recommendedMaxPlayers = recommendedMaxPlayers;
        this.recommendedPreloadRadius = recommendedPreloadRadius;
        this.preferAggressiveNetworkBudget = preferAggressiveNetworkBudget;
    }

    public int recommendedMaxPlayers() {
        return this.recommendedMaxPlayers;
    }

    public int recommendedPreloadRadius() {
        return this.recommendedPreloadRadius;
    }

    public boolean preferAggressiveNetworkBudget() {
        return this.preferAggressiveNetworkBudget;
    }
}
