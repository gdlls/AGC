package agc.bench;

/**
 * A benchmark scenario drives every connected bot once per server tick (20 Hz).
 */
public interface Scenario {

    String name();

    /** Total scenario length in ticks (20 ticks = 1 second). */
    int durationTicks();

    /** Called at 20 Hz for each connected bot. Must be cheap and non-blocking. */
    void onBotTick(BotHandle bot, int tick);

    /**
     * Console commands executed once via RCON before bots spawn (e.g. gamemode setup).
     * Return an empty array when no setup is required.
     */
    default String[] serverSetupCommands() {
        return new String[0];
    }

    /** Login-churn scenarios are driven by {@link BotFarm}'s join/leave scheduler instead of the tick loop. */
    default boolean isLoginChurn() {
        return false;
    }

    /** Target join/leave operations per second for login-churn scenarios. */
    default double churnPerSecond() {
        return 0.0;
    }
}
