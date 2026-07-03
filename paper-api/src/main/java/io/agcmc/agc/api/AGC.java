package io.agcmc.agc.api;

import io.agcmc.agc.api.compat.AGCCompatibility;
import io.agcmc.agc.api.minigame.AGCMinigames;
import org.jetbrains.annotations.NotNull;

/**
 * Public AGC API entry point.
 * <p>
 * The API is intentionally Paper/Bukkit compatible: plugin-facing helpers keep
 * mutating operations on the primary thread unless a plugin explicitly uses an
 * asynchronous safe method.
 */
public final class AGC {
    private static final AGCMinigames MINIGAMES = new AGCMinigames();
    private static final AGCScheduler SCHEDULER = new AGCScheduler();
    private static final AGCPerformance PERFORMANCE = new AGCPerformance();
    private static final AGCCompatibility COMPATIBILITY = new AGCCompatibility();

    private AGC() {
    }

    /**
     * Mini-game arena, team, match, countdown and player snapshot helpers.
     *
     * @return the process-wide AGC mini-game service
     */
    public static @NotNull AGCMinigames minigames() {
        return MINIGAMES;
    }

    /**
     * Main-thread and region-hinted scheduling helpers.
     *
     * @return the AGC scheduler facade
     */
    public static @NotNull AGCScheduler scheduler() {
        return SCHEDULER;
    }

    /**
     * Lightweight performance/profile feature information for plugins.
     *
     * @return the AGC performance facade
     */
    public static @NotNull AGCPerformance performance() {
        return PERFORMANCE;
    }

    /**
     * Plugin compatibility contract registry. Declarations are conservative hints
     * for AGC no-invasion lanes and never permit off-thread Bukkit mutations.
     *
     * @return the AGC compatibility registry
     */
    public static @NotNull AGCCompatibility compatibility() {
        return COMPATIBILITY;
    }
}
