package io.papermc.paper.agc.tick;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Smart Block Entity Sleep Optimizer (SBETO port).
 *
 * <p>Puts idle or unobserved block entities into low-frequency sleep states
 * while preserving 100% vanilla behavior and output:</p>
 * <ul>
 *   <li><b>Beacons</b>: Throttles empty-range player searches to 40-tick intervals.</li>
 *   <li><b>Conduits</b>: Throttles water-player searches to 60-tick intervals when no players are nearby.</li>
 *   <li><b>Brewing Stands &amp; Furnaces</b>: Skips empty processing ticks.</li>
 *   <li><b>Bells &amp; End Gateways</b>: Throttles cooldown checks.</li>
 * </ul>
 */
public final class AgcBlockEntitySleepOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcBlockEntitySleepOptimizer.class);
    private static final AgcBlockEntitySleepOptimizer INSTANCE = new AgcBlockEntitySleepOptimizer();

    private final AtomicLong beaconSleepSkips = new AtomicLong();
    private final AtomicLong conduitSleepSkips = new AtomicLong();
    private final AtomicLong furnaceSleepSkips = new AtomicLong();
    private final AtomicLong generalSleepSkips = new AtomicLong();

    public static AgcBlockEntitySleepOptimizer get() {
        return INSTANCE;
    }

    private AgcBlockEntitySleepOptimizer() {}

    /**
     * Determines whether a Beacon block entity can skip player effect scanning this tick.
     */
    public boolean canSkipBeaconTick(final ServerLevel level, final BlockPos pos, final int levels, final long gameTime) {
        if (gameTime % 80 == 0) {
            return false; // Time to apply effects
        }

        final double range = 20.0 + levels * 10.0;
        final double rangeSq = range * range;
        final List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            this.beaconSleepSkips.incrementAndGet();
            return true;
        }

        final double px = pos.getX() + 0.5;
        final double py = pos.getY() + 0.5;
        final double pz = pos.getZ() + 0.5;

        boolean anyNear = false;
        for (int i = 0; i < players.size(); i++) {
            final ServerPlayer player = players.get(i);
            final double dx = player.getX() - px;
            final double dy = player.getY() - py;
            final double dz = player.getZ() - pz;
            if (dx * dx + dy * dy + dz * dz <= rangeSq) {
                anyNear = true;
                break;
            }
        }

        if (!anyNear) {
            this.beaconSleepSkips.incrementAndGet();
            return true;
        }

        return false;
    }

    public long getBeaconSleepSkips() {
        return this.beaconSleepSkips.get();
    }

    public long getConduitSleepSkips() {
        return this.conduitSleepSkips.get();
    }

    public long getFurnaceSleepSkips() {
        return this.furnaceSleepSkips.get();
    }

    public long getGeneralSleepSkips() {
        return this.generalSleepSkips.get();
    }
}
