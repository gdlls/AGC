package io.papermc.paper.agc.redstone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Redstone Signal Caching, Wire Network Topological Cache & Infinite Loop Breaker.
 *
 * <p>Optimizes redstone and observer propagation by filtering no-op power updates,
 * caching chunk-boundary wire signals, caching contiguous wire network structures,
 * coalescing wire power recalculations, and terminating high-frequency clock lag machines.</p>
 */
public final class AgcRedstoneOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcRedstoneOptimizer.class);
    private static final AgcRedstoneOptimizer INSTANCE = new AgcRedstoneOptimizer();

    public static final int MAX_OBSERVER_CHAIN_DEPTH = 64;
    public static final int MAX_CLOCK_FLIPS_PER_WINDOW = 50;
    public static final long CLOCK_WINDOW_TICKS = 20L;

    private static final ThreadLocal<int[]> OBSERVER_CHAIN_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private final ConcurrentHashMap<Long, Byte> boundaryPowerCache = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, WireNetworkSnapshot> networkCache = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, FlipTracker> flipTrackers = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, Byte> pendingWireUpdates = new ConcurrentHashMap<>();

    private final AtomicLong redundantUpdatesFiltered = new AtomicLong();
    private final AtomicLong observerLoopsBroken = new AtomicLong();
    private final AtomicLong boundaryCacheHits = new AtomicLong();
    private final AtomicLong clockLagMachinesSuppressed = new AtomicLong();
    private final AtomicLong wireNetworksCached = new AtomicLong();
    private final AtomicLong wireUpdatesCoalesced = new AtomicLong();

    public static AgcRedstoneOptimizer get() {
        return INSTANCE;
    }

    private AgcRedstoneOptimizer() {}

    /**
     * Checks if a block update can be skipped because the calculated power level is unchanged.
     */
    public boolean filterRedundantUpdate(final int previousPower, final int newPower) {
        if (previousPower == newPower) {
            this.redundantUpdatesFiltered.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Tracks observer propagation depth and terminates infinite loop chains beyond the safety ceiling.
     *
     * <p>Fix: a rejected check must NOT consume depth budget. The previous
     * {@code depth[0]++ >= MAX} form incremented even on rejection, and the NMS
     * reject path returns before the matching decrement — leaking +1 per
     * truncation, unbounded over server lifetime, until legitimate shallow chains
     * start getting cut. Rejections are now budget-neutral.</p>
     */
    public boolean checkObserverChainDepth() {
        final int[] depth = OBSERVER_CHAIN_DEPTH.get();
        if (depth[0] >= MAX_OBSERVER_CHAIN_DEPTH) {
            this.observerLoopsBroken.incrementAndGet();
            return false;
        }
        depth[0]++;
        return true;
    }

    /**
     * Resets current thread's observer chain recursion depth counter.
     */
    public void resetObserverChainDepth() {
        OBSERVER_CHAIN_DEPTH.get()[0] = 0;
    }

    /**
     * Decrements current thread's observer chain recursion depth upon stack unwind.
     */
    public void decrementObserverChainDepth() {
        final int[] depth = OBSERVER_CHAIN_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    public static final int MAX_TRACKED_BLOCKS = 32768;

    /**
     * Tracks state flip frequency for a block to detect and suppress runaway high-frequency clock lag machines.
     *
     * @param packedPos Packed block coordinate
     * @param currentTick Current world tick
     * @return true if update is permitted, false if suppressed as runaway clock
     */
    public boolean trackClockFrequency(final long packedPos, final long currentTick) {
        if (this.flipTrackers.size() > MAX_TRACKED_BLOCKS) {
            this.flipTrackers.clear();
        }
        final FlipTracker tracker = this.flipTrackers.compute(packedPos, (k, existing) -> {
            if (existing == null || currentTick < existing.windowStartTick || (currentTick - existing.windowStartTick) >= CLOCK_WINDOW_TICKS) {
                return new FlipTracker(currentTick, 1);
            }
            existing.flips++;
            return existing;
        });

        if (tracker.flips > MAX_CLOCK_FLIPS_PER_WINDOW) {
            this.clockLagMachinesSuppressed.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Caches or retrieves a chunk boundary redstone wire signal level.
     */
    public byte getOrCacheBoundarySignal(final long packedPos, final java.util.function.Supplier<Byte> signalSupplier) {
        final Byte cached = this.boundaryPowerCache.get(packedPos);
        if (cached != null) {
            this.boundaryCacheHits.incrementAndGet();
            return cached;
        }
        final byte signal = signalSupplier != null ? signalSupplier.get() : 0;
        if (this.boundaryPowerCache.size() > MAX_TRACKED_BLOCKS) {
            this.boundaryPowerCache.clear();
        }
        this.boundaryPowerCache.put(packedPos, signal);
        return signal;
    }

    public void invalidateBoundarySignal(final long packedPos) {
        this.boundaryPowerCache.remove(packedPos);
        this.networkCache.remove(packedPos);
    }

    /**
     * Stores or retrieves a cached wire network topology.
     */
    public WireNetworkSnapshot getOrCacheNetwork(final long networkRootKey, final java.util.function.Supplier<WireNetworkSnapshot> supplier) {
        final WireNetworkSnapshot existing = this.networkCache.get(networkRootKey);
        if (existing != null) {
            return existing;
        }
        if (supplier == null) return null;
        final WireNetworkSnapshot snapshot = supplier.get();
        if (snapshot != null) {
            if (this.networkCache.size() > MAX_TRACKED_BLOCKS) {
                this.networkCache.clear();
            }
            this.networkCache.put(networkRootKey, snapshot);
            this.wireNetworksCached.incrementAndGet();
        }
        return snapshot;
    }

    /**
     * Coalesces a wire power update to prevent redundant intermediate calculations.
     */
    public void coalesceWireUpdate(final long packedPos, final byte targetPower) {
        final Byte prev = this.pendingWireUpdates.put(packedPos, targetPower);
        if (prev != null) {
            this.wireUpdatesCoalesced.incrementAndGet();
        }
    }

    public int drainCoalescedWireUpdates(final java.util.function.BiConsumer<Long, Byte> consumer) {
        if (this.pendingWireUpdates.isEmpty()) return 0;
        int count = 0;
        for (final Long key : this.pendingWireUpdates.keySet()) {
            final Byte power = this.pendingWireUpdates.remove(key);
            if (power != null) {
                if (consumer != null) consumer.accept(key, power);
                count++;
            }
        }
        return count;
    }

    public void clear() {
        this.boundaryPowerCache.clear();
        this.networkCache.clear();
        this.flipTrackers.clear();
        this.pendingWireUpdates.clear();
        this.redundantUpdatesFiltered.set(0);
        this.observerLoopsBroken.set(0);
        this.boundaryCacheHits.set(0);
        this.clockLagMachinesSuppressed.set(0);
        this.wireNetworksCached.set(0);
        this.wireUpdatesCoalesced.set(0);
        resetObserverChainDepth();
    }

    public static final class FlipTracker {
        long windowStartTick;
        int flips;

        FlipTracker(final long windowStartTick, final int flips) {
            this.windowStartTick = windowStartTick;
            this.flips = flips;
        }
    }

    public record WireNetworkSnapshot(
        long[] wirePositions,
        byte[] wirePowers
    ) {}

    public RedstoneOptimizerMetrics metrics() {
        return new RedstoneOptimizerMetrics(
            this.boundaryPowerCache.size(),
            this.networkCache.size(),
            this.redundantUpdatesFiltered.get(),
            this.observerLoopsBroken.get(),
            this.boundaryCacheHits.get(),
            this.clockLagMachinesSuppressed.get(),
            this.wireNetworksCached.get(),
            this.wireUpdatesCoalesced.get()
        );
    }

    public record RedstoneOptimizerMetrics(
        int boundaryCacheSize,
        int networkCacheSize,
        long redundantUpdatesFiltered,
        long observerLoopsBroken,
        long boundaryCacheHits,
        long clockLagMachinesSuppressed,
        long wireNetworksCached,
        long wireUpdatesCoalesced
    ) {}
}
