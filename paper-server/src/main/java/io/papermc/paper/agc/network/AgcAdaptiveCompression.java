package io.papermc.paper.agc.network;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AGC — Adaptive Compression Controller (UniverseSpigot / SteelMC port).
 *
 * <p>Dynamically scales compression threshold and effort based on server MSPT:</p>
 * <ul>
 *   <li>MSPT &lt; 25.0 ms: 256 bytes (maximum network compression)</li>
 *   <li>MSPT &lt; 40.0 ms: 512 bytes (balanced nominal)</li>
 *   <li>MSPT &lt; 55.0 ms: 768 bytes (reduced CPU load)</li>
 *   <li>MSPT &ge; 55.0 ms: 1024 bytes (emergency CPU preservation)</li>
 * </ul>
 */
public final class AgcAdaptiveCompression {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcAdaptiveCompression.class);
    private static final AgcAdaptiveCompression INSTANCE = new AgcAdaptiveCompression();

    public static AgcAdaptiveCompression get() {
        return INSTANCE;
    }

    private AgcAdaptiveCompression() {}

    /**
     * Returns the dynamic compression threshold based on current server MSPT.
     */
    public int getDynamicThreshold(final int baseThreshold) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return baseThreshold;
        }

        final double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        if (mspt < 25.0) {
            return Math.max(256, baseThreshold);
        } else if (mspt < 40.0) {
            return Math.max(512, baseThreshold);
        } else if (mspt < 55.0) {
            return Math.max(768, baseThreshold);
        } else {
            return Math.max(1024, baseThreshold);
        }
    }
}
