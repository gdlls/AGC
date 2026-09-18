package io.papermc.paper.agc.worldgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;

/**
 * AGC — Giant-NBT Early-Bounds Prune (Structure Layout Optimizer port, part 2).
 *
 * <p>Vanilla loads the ENTIRE structure NBT into memory and runs every
 * {@code StructureProcessor} over all positions, then discards positions outside
 * the generating chunk. For giant NBTs (trial chambers, ancient cities) this is
 * pure waste. This engine pre-filters palette positions by chunk bounds
 * <i>before</i> processors run — processors then see only relevant entries.</p>
 *
 * <p>Parity guard: pieces whose processors override {@code finalizeProcessing}
 * (e.g. Trail Ruins' capped processor) may need ALL positions to function, so
 * callers MUST pass {@code hasFinalizeProcessing=true} for them — pruning is then
 * bypassed and vanilla behavior runs untouched. The filter itself is exact
 * (inclusive bounds), so pruned runs produce identical blocks.</p>
 */
public final class AgcStructureNbtPruner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcStructureNbtPruner.class);
    private static final AgcStructureNbtPruner INSTANCE = new AgcStructureNbtPruner();

    private final AtomicLong pruneCalls = new AtomicLong();
    private final AtomicLong positionsScanned = new AtomicLong();
    private final AtomicLong positionsKept = new AtomicLong();
    private final AtomicLong bypassedFinalize = new AtomicLong();

    public static AgcStructureNbtPruner get() {
        return INSTANCE;
    }

    private AgcStructureNbtPruner() {}

    /**
     * Filters palette indices to those inside the chunk bounds.
     *
     * @param xs {@code x} per palette entry (local structure coords)
     * @param ys {@code y} per palette entry
     * @param zs {@code z} per palette entry
     * @param count entry count
     * @param minX/maxX inclusive chunk-local bounds (already translated)
     * @param hasFinalizeProcessing {@code true} bypasses pruning (parity guard)
     * @return indices of entries to process (fresh array, caller-owned)
     */
    public int[] filterIndices(
        final int[] xs, final int[] ys, final int[] zs, final int count,
        final int minX, final int minY, final int minZ,
        final int maxX, final int maxY, final int maxZ,
        final boolean hasFinalizeProcessing
    ) {
        this.pruneCalls.incrementAndGet();
        if (hasFinalizeProcessing) {
            this.bypassedFinalize.incrementAndGet();
            return identityRange(count);
        }
        if (xs == null || ys == null || zs == null || count <= 0) {
            return new int[0];
        }
        final int limit = Math.min(count, Math.min(xs.length, Math.min(ys.length, zs.length)));
        int[] kept = new int[Math.min(limit, 256)];
        int n = 0;
        for (int i = 0; i < limit; i++) {
            this.positionsScanned.incrementAndGet();
            final int x = xs[i];
            final int y = ys[i];
            final int z = zs[i];
            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                if (n >= kept.length) {
                    final int[] grown = new int[Math.min(limit, kept.length * 2)];
                    System.arraycopy(kept, 0, grown, 0, n);
                    kept = grown;
                }
                kept[n++] = i;
                this.positionsKept.incrementAndGet();
            }
        }
        final int[] out = new int[n];
        System.arraycopy(kept, 0, out, 0, n);
        return out;
    }

    /**
     * Object-based variant for block-info lists: keeps entries whose position
     * predicate matches. Zero intermediate collections beyond the result.
     */
    public <T> List<T> filterEntries(final List<T> entries, final IntPredicate indexInside) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        final List<T> kept = new ArrayList<>(Math.min(entries.size(), 64));
        for (int i = 0, n = entries.size(); i < n; i++) {
            this.positionsScanned.incrementAndGet();
            if (indexInside == null || indexInside.test(i)) {
                kept.add(entries.get(i));
                this.positionsKept.incrementAndGet();
            }
        }
        this.pruneCalls.incrementAndGet();
        return kept;
    }

    private static int[] identityRange(final int count) {
        final int n = Math.max(0, count);
        final int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = i;
        }
        return out;
    }

    public void clear() {
        this.pruneCalls.set(0);
        this.positionsScanned.set(0);
        this.positionsKept.set(0);
        this.bypassedFinalize.set(0);
    }

    public PrunerMetrics metrics() {
        return new PrunerMetrics(
            this.pruneCalls.get(),
            this.positionsScanned.get(),
            this.positionsKept.get(),
            this.bypassedFinalize.get()
        );
    }

    public record PrunerMetrics(long pruneCalls, long positionsScanned, long positionsKept, long bypassedFinalize) {}
}
