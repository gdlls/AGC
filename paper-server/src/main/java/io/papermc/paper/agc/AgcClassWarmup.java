package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AGC — Post-startup classload warmup.
 *
 * <p>Evidence: intermittent multi-second tick stalls (watchdog dumps, run-smoke logs
 * 2026-09-06-7 / 2026-09-09-2) all share one shape — the Server thread blocks inside
 * a <i>first-use lazy classload</i> during gameplay:</p>
 *
 * <ul>
 *   <li>{@code CraftEntityTypes.<clinit>} while loading entities from a chunk
 *       (its static init builds maps for every entity type),</li>
 *   <li>{@code ServerStatsCounter} codec graph spin-up on the first stats save
 *       (dispatched-map codec per stat type),</li>
 *   <li>{@code NbtIo.writeCompressed} first call pulling in the Deflater native path.</li>
 * </ul>
 *
 * <p>This engine runs the same first-use paths once, on a background worker, right
 * after the server reaches the tick loop. Nothing here mutates world state: class
 * initialization is JVM-synchronized (whoever runs it first wins; later callers see
 * the initialized class), the stats codec warm encodes an empty map, and the NBT
 * warm compresses an empty tag into an in-memory buffer.</p>
 *
 * <p>Fail-open: every step is independent; a throwing step is logged and skipped.
 * In VANILLA mode the warmup is skipped entirely (vanilla promises untouched behavior).</p>
 */
public final class AgcClassWarmup {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcClassWarmup.class);

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile boolean warmed;

    // Package-private knobs for tests: zero out the settle delay and inject
    // lightweight steps instead of the NMS-touching defaults.
    static volatile long startDelayMillis = 2_000L;

    private AgcClassWarmup() {
    }

    /** A single named warmup step. Never throws upward — implementations must catch. */
    public interface WarmStep {
        String name();

        void run() throws Exception;
    }

    /**
     * Kicks off the warmup on a background thread. Idempotent: only the first call wins.
     * Safe to call from any thread; returns immediately.
     */
    public static void kickOff() {
        kickOff(defaultSteps());
    }

    static void kickOff(final List<WarmStep> steps) {
        if (AgcCapabilityMatrix.getMode() == AgcCapabilityMatrix.Mode.VANILLA) {
            return; // VANILLA mode: untouched behavior, no warmup thread.
        }
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        final Thread t = new Thread(() -> {
            try {
                // Let the server settle into its first ticks; warmup is not urgent,
                // it only has to beat the first mid-gameplay lazy classload.
                Thread.sleep(startDelayMillis);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            runWarmup(steps);
        }, "agc-class-warmup");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    static void runWarmup(final List<WarmStep> steps) {
        final long start = System.nanoTime();
        int done = 0;
        for (final WarmStep step : steps) {
            try {
                step.run();
                done++;
            } catch (final Throwable th) {
                LOGGER.warn("[AGC] class warmup step '{}' failed (ignored, fail-open): {}", step.name(), th.toString());
            }
        }
        warmed = true;
        LOGGER.info("[AGC] class warmup complete ({} steps, {} ms)",
            done, (System.nanoTime() - start) / 1_000_000L);
    }

    /** True once the warmup has finished (all steps attempted). */
    public static boolean isWarmed() {
        return warmed;
    }

    // AGC start - test hook
    static void resetForTest() {
        STARTED.set(false);
        warmed = false;
    }
    // AGC end

    /**
     * The default warmup steps. Named so failures are attributable in logs.
     *
     * <p>Package-visible for tests: tests execute the JDK-only subset.</p>
     */
    static List<WarmStep> defaultSteps() {
        return List.of(
            new WarmStep() {
                @Override
                public String name() {
                    return "craft-entity-types-clinit";
                }

                @Override
                public void run() throws Exception {
                    // Watchdog evidence: CraftEntityTypes.<clinit> (builds per-entity-type
                    // adapter maps) ran on the tick thread during chunk entity load.
                    Class.forName("org.bukkit.craftbukkit.entity.CraftEntityTypes", true, AgcClassWarmup.class.getClassLoader());
                    Class.forName("org.bukkit.craftbukkit.entity.CraftEntity", true, AgcClassWarmup.class.getClassLoader());
                }
            },
            new WarmStep() {
                @Override
                public String name() {
                    return "stats-codec-graph";
                }

                @Override
                public void run() throws Exception {
                    // Watchdog evidence: first stats save spun up the whole dispatched-map
                    // codec graph (registry byNameCodec + DataResult machinery) on the tick
                    // thread; stats parse on player login pays the same class inits. Class
                    // initialization builds the codec graph without touching any player.
                    Class.forName("net.minecraft.stats.ServerStatsCounter", true, AgcClassWarmup.class.getClassLoader());
                }
            },
            new WarmStep() {
                @Override
                public String name() {
                    return "nbt-deflater";
                }

                @Override
                public void run() throws Exception {
                    // Watchdog evidence: player-data save stalled on first compressed NBT
                    // write (Deflater native init). Compress an empty tag into memory.
                    final java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
                    net.minecraft.nbt.NbtIo.writeCompressed(new net.minecraft.nbt.CompoundTag(), sink);
                }
            }
        );
    }
}
