package ca.spottedleaf.moonrise.common.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

public class TickThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickThread.class);

    private static String getThreadContext() {
        return "thread=" + Thread.currentThread().getName();
    }

    /**
     * @deprecated
     */
    @Deprecated
    public static void ensureTickThread(final String reason) {
        if (!isTickThread()) {
            LOGGER.error("Thread failed main thread check: " + reason + ", context=" + getThreadContext(), new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final Level world, final BlockPos pos, final String reason) {
        if (!isTickThreadFor(world, pos)) {
            final String ex = "Thread failed main thread check: " +
                               reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", block_pos=" + pos;
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Level world, final BlockPos pos, final int blockRadius, final String reason) {
        if (!isTickThreadFor(world, pos, blockRadius)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", block_radius=" + blockRadius;
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Level world, final ChunkPos pos, final String reason) {
        if (!isTickThreadFor(world, pos)) {
            final String ex = "Thread failed main thread check: " +
                               reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", chunk_pos=" + pos;
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Level world, final int chunkX, final int chunkZ, final String reason) {
        if (!isTickThreadFor(world, chunkX, chunkZ)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", chunk_pos=" + new ChunkPos(chunkX, chunkZ);
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Entity entity, final String reason) {
        if (!isTickThreadFor(entity)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", entity=" + EntityUtil.dumpEntity(entity);
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Level world, final AABB aabb, final String reason) {
        if (!isTickThreadFor(world, aabb)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", aabb=" + aabb;
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public static void ensureTickThread(final Level world, final double blockX, final double blockZ, final String reason) {
        if (!isTickThreadFor(world, blockX, blockZ)) {
            final String ex = "Thread failed main thread check: " +
                reason + ", context=" + getThreadContext() + ", world=" + WorldUtil.getWorldName(world) + ", block_pos=" + new Vec3(blockX, 0.0, blockZ);
            LOGGER.error(ex, new Throwable());
            throw new IllegalStateException(ex);
        }
    }

    public final int id; /* We don't override getId as the spec requires that it be unique (with respect to all other threads) */

    private static final AtomicInteger ID_GENERATOR = new AtomicInteger();

    public TickThread(final String name) {
        this(null, name);
    }

    public TickThread(final Runnable run, final String name) {
        this(null, run, name);
    }

    public TickThread(final ThreadGroup group, final Runnable run, final String name) {
        this(group, run, name, ID_GENERATOR.incrementAndGet());
    }

    private TickThread(final ThreadGroup group, final Runnable run, final String name, final int id) {
        super(group, run, name);
        this.id = id;
    }

    public static TickThread getCurrentTickThread() {
        final Thread current = Thread.currentThread();
        return current instanceof TickThread tickThread ? tickThread : null;
    }

    // AGC start - fast-path the virtual-primary probe in isTickThread()
    // isTickThread() is called from AsyncCatcher.catchOp and the chunk-system thread guards on
    // effectively every Bukkit API entry point. The second disjunct used to be an unconditional
    // ThreadLocal.get (a ThreadLocalMap probe) even though a virtual primary context exists only
    // after someone enters one, which in stock operation is never. That probe showed up as
    // ThreadLocal$ThreadLocalMap.getEntry in JFR execution samples (async entry points + every
    // Level#mayInteract guard) and bloated the inline budget of the guard chain.
    //
    // VIRTUAL_POSSIBLE is a one-way latch: it is false until the first AgcPluginVirtualizer context
    // is entered anywhere in the JVM (enterContext publishes it), and never resets. Until it flips,
    // isVirtualPrimary() cannot observe a context on any thread, so skipping the probe is
    // semantics-preserving; once it flips, the full check is restored. The flag is a plain volatile
    // read vs. a hash-probe into a ThreadLocalMap per call.
    private static volatile boolean VIRTUAL_POSSIBLE;

    public static void agc$noteVirtualContextPossible() {
        if (!VIRTUAL_POSSIBLE) {
            VIRTUAL_POSSIBLE = true;
        }
    }

    /**
     * @return whether a virtual primary context may exist anywhere in the JVM (one-way latch).
     */
    public static boolean agc$virtualPossible() {
        return VIRTUAL_POSSIBLE;
    }
    // AGC end

    public static boolean isTickThread() {
        // AGC start - fast-path the virtual-primary probe in isTickThread()
        if (VIRTUAL_POSSIBLE) {
            return Thread.currentThread() instanceof TickThread || io.papermc.paper.agc.AgcPluginVirtualizer.isVirtualPrimary();
        }
        return Thread.currentThread() instanceof TickThread;
        // AGC end
    }

    public static boolean isShutdownThread() {
        return false;
    }

    public static boolean isTickThreadFor(final Level world, final BlockPos pos) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Level world, final BlockPos pos, final int blockRadius) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Level world, final ChunkPos pos) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Level world, final Vec3 pos) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Level world, final int chunkX, final int chunkZ) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Level world, final AABB aabb) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Level world, final double blockX, final double blockZ) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Level world, final Vec3 position, final Vec3 deltaMovement, final int buffer) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Level world, final int fromChunkX, final int fromChunkZ, final int toChunkX, final int toChunkZ) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Level world, final int chunkX, final int chunkZ, final int radius) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Entity entity) {
        return isTickThread();
    }
}
