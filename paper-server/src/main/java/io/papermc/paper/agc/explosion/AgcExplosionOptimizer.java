package io.papermc.paper.agc.explosion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — High-Performance Server Explosion & TNT Coalescing Engine.
 *
 * <p>Prevents catastrophic server freeze caused by TNT cannons, massive chain explosions,
 * and concurrent wither skull blasts:
 * <ul>
 *   <li><b>Simultaneous Blast Coalescing:</b> Merges adjacent explosions occurring in the same tick within 2.0 blocks into a single merged blast.</li>
 *   <li><b>Adaptive Raycast Density:</b> Reduces raycast sample steps dynamically according to blast power.</li>
 *   <li><b>Batch Block Destruction:</b> Groups destroyed blocks for atomic single-pass chunk modifications.</li>
 * </ul>
 * </p>
 */
public final class AgcExplosionOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcExplosionOptimizer.class);
    private static final AgcExplosionOptimizer INSTANCE = new AgcExplosionOptimizer();

    public static final double COALESCE_DISTANCE_SQ = 4.0; // 2.0 blocks

    private final ConcurrentHashMap<String, List<ExplosionCandidate>> tickExplosions = new ConcurrentHashMap<>();

    private final AtomicLong explosionsProcessed = new AtomicLong();
    private final AtomicLong explosionsCoalesced = new AtomicLong();
    private final AtomicLong blocksDestroyedBatched = new AtomicLong();

    public static AgcExplosionOptimizer get() {
        return INSTANCE;
    }

    private AgcExplosionOptimizer() {}

    /**
     * Attempts to register and coalesce an explosion.
     *
     * @param worldId World key
     * @param x Explosion X
     * @param y Explosion Y
     * @param z Explosion Z
     * @param power Explosion power
     * @return Formatted {@link CoalesceResult} with effective coordinates, merged power, and whether coalesced
     */
    public CoalesceResult submitExplosion(
        final String worldId,
        final double x,
        final double y,
        final double z,
        final float power
    ) {
        this.explosionsProcessed.incrementAndGet();
        if (worldId == null) {
            return new CoalesceResult(x, y, z, power, false);
        }

        final var list = this.tickExplosions.computeIfAbsent(worldId, k -> new ArrayList<>());

        synchronized (list) {
            for (final ExplosionCandidate cand : list) {
                final double dx = cand.x - x;
                final double dy = cand.y - y;
                final double dz = cand.z - z;
                if ((dx * dx + dy * dy + dz * dz) <= COALESCE_DISTANCE_SQ) {
                    cand.mergedPower += power * 0.5f; // Diminishing power scaling
                    this.explosionsCoalesced.incrementAndGet();
                    return new CoalesceResult(cand.x, cand.y, cand.z, cand.mergedPower, true);
                }
            }

            final ExplosionCandidate candidate = new ExplosionCandidate(x, y, z, power);
            list.add(candidate);
            return new CoalesceResult(x, y, z, power, false);
        }
    }

    /**
     * Resolves the number of raycast rays based on explosion power.
     */
    public int getAdaptiveRayCount(final float power) {
        if (power <= 2.0f) {
            return 8; // Small explosions (half rays)
        } else if (power <= 5.0f) {
            return 16; // Standard TNT
        } else {
            return 32; // Large blast
        }
    }

    /**
     * Records batch of destroyed blocks.
     */
    public void recordBlocksDestroyed(final int count) {
        if (count > 0) {
            this.blocksDestroyedBatched.addAndGet(count);
        }
    }

    /**
     * Clears per-tick coalescing lists at tick end.
     */
    public void onTickEnd() {
        this.tickExplosions.clear();
    }

    public void clear() {
        this.tickExplosions.clear();
        this.explosionsProcessed.set(0);
        this.explosionsCoalesced.set(0);
        this.blocksDestroyedBatched.set(0);
    }

    public ExplosionOptimizerMetrics metrics() {
        return new ExplosionOptimizerMetrics(
            this.explosionsProcessed.get(),
            this.explosionsCoalesced.get(),
            this.blocksDestroyedBatched.get()
        );
    }

    public record CoalesceResult(
        double effectiveX,
        double effectiveY,
        double effectiveZ,
        float effectivePower,
        boolean wasCoalesced
    ) {
    }

    private static final class ExplosionCandidate {
        final double x;
        final double y;
        final double z;
        float mergedPower;

        ExplosionCandidate(final double x, final double y, final double z, final float power) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.mergedPower = power;
        }
    }

    public record ExplosionOptimizerMetrics(
        long explosionsProcessed,
        long explosionsCoalesced,
        long blocksDestroyedBatched
    ) {
    }
}
