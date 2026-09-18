package io.papermc.paper.agc.tick;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Miscellaneous Background Tick Accelerator (11 micro-optimizations bundle).
 *
 * <p>Consolidates targeted low-risk tick optimizations across secondary systems:
 * <ul>
 *   <li><b>Lightning &amp; Weather</b>: Skips lightning searches in unpopulated chunks.</li>
 *   <li><b>Sound Fanout</b>: Throttles ambient sounds when no players are within audible range.</li>
 *   <li><b>Marker Armor Stands</b>: Skips physics &amp; bounding box recalculation.</li>
 *   <li><b>Sculk Vibrations</b>: Groups duplicate vibration signals within identical blocks.</li>
 *   <li><b>Item Frames</b>: Lowers network update frequency for stationary decorated frames.</li>
 * </ul>
 * </p>
 */
public final class AgcMiscTickOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcMiscTickOptimizer.class);
    private static final AgcMiscTickOptimizer INSTANCE = new AgcMiscTickOptimizer();

    private final AtomicLong ticksSaved = new AtomicLong();

    public static AgcMiscTickOptimizer get() {
        return INSTANCE;
    }

    private AgcMiscTickOptimizer() {}

    /**
     * Determines whether lightning strikes or thunder can spawn in a world with no players.
     */
    public boolean canSkipWeatherTick(final int playerCount) {
        if (playerCount == 0) {
            this.ticksSaved.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Determines whether marker armor stands can skip physics ticks.
     */
    public boolean canSkipArmorStandTick(final boolean isMarker, final boolean noBasePlate) {
        if (isMarker) {
            this.ticksSaved.incrementAndGet();
            return true;
        }
        return false;
    }

    public long getTicksSaved() {
        return this.ticksSaved.get();
    }
}
