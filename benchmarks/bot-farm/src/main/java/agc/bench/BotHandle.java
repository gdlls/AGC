package agc.bench;

/**
 * Abstraction over a single connected bot so scenarios never touch MCProtocolLib APIs directly
 * (keeps version drift isolated to {@link BotClient}).
 */
public interface BotHandle {

    /** Performs the connection + offline-mode login handshake. */
    void connect();

    boolean isConnected();

    /** Sends a client-side position update, walking toward (x, z) at walking speed. */
    void moveToward(double x, double z);

    /** Sends a look update. */
    void setLook(float yaw, float pitch);

    /** Swings the arm (animation + basic interaction pressure). */
    void swingArm();

    /** Sends a chat message (rate-limited by caller). */
    void chat(String message);

    /** Runs a server-side command as this bot, e.g. {@code /tp 100 64 100}. */
    void runCommand(String commandWithoutSlash);

    /** Disconnects the session cleanly. */
    void disconnect();

    /** Number of ticks this bot has been ticked. */
    default int getAgeTicks() { return 0; }

    default int incrementAge() { return 0; }
 
    default void setAgeTicks(int age) {}
}
