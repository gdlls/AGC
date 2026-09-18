package io.papermc.paper.agc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Palette Copy-On-Write (COW) & Section Memory Optimizer.
 *
 * <p>In servers with 500+ multi-worlds and millions of chunk sections, a massive portion
 * of sections consist of uniform blocks (e.g. pure air, bedrock, ocean water, stone fill).
 * Standard palette allocations allocate array backings and HashMap/identity structures for every section.</p>
 *
 * <p>This optimizer provides:
 * <ul>
 *   <li>Single-state shared singleton palettes (0 bits per entry, O(1) memory footprint)</li>
 *   <li>Copy-On-Write (COW) semantics: mutation transparently expands singleton into a full mutable palette</li>
 *   <li>Section memory profiling & telemetry (total bytes saved, deduplicated sections)</li>
 * </ul>
 * </p>
 */
public final class AgcPaletteCowOptimizer {

    private static final AgcPaletteCowOptimizer INSTANCE = new AgcPaletteCowOptimizer();

    private final Map<Integer, SharedSingleStatePalette> sharedPalettes = new ConcurrentHashMap<>();

    private final AtomicLong sectionsOptimized = new AtomicLong();
    private final AtomicLong cowExpansions = new AtomicLong();
    private final AtomicLong estimatedBytesSaved = new AtomicLong();

    public static AgcPaletteCowOptimizer get() {
        return INSTANCE;
    }

    private AgcPaletteCowOptimizer() {}

    /**
     * Obtains or creates a shared immutable single-state palette for a uniform section.
     *
     * @param stateId The global block state ID
     * @return Shared immutable single-state palette
     */
    public SharedSingleStatePalette getSharedPalette(final int stateId) {
        return this.sharedPalettes.computeIfAbsent(stateId, id -> new SharedSingleStatePalette(id, this));
    }

    /**
     * Analyzes section data and optimizes it to a shared single-state palette if uniform.
     *
     * @param blockStateIds Array of 4096 block state IDs in the 16x16x16 chunk section
     * @return {@link PaletteOptimizationResult} containing either the shared palette or mutable palette
     */
    public PaletteOptimizationResult optimizeSection(final int[] blockStateIds) {
        if (blockStateIds == null || blockStateIds.length == 0) {
            return new PaletteOptimizationResult(getSharedPalette(0), true);
        }

        final int first = blockStateIds[0];
        boolean uniform = true;
        for (int i = 1; i < blockStateIds.length; i++) {
            if (blockStateIds[i] != first) {
                uniform = false;
                break;
            }
        }

        if (uniform) {
            this.sectionsOptimized.incrementAndGet();
            this.estimatedBytesSaved.addAndGet(2048L);
            return new PaletteOptimizationResult(getSharedPalette(first), true);
        }

        return new PaletteOptimizationResult(new MutableSectionPalette(blockStateIds), false);
    }

    void recordCowExpansion() {
        this.cowExpansions.incrementAndGet();
        this.estimatedBytesSaved.addAndGet(-2048L);
    }

    public void resetMetrics() {
        this.sharedPalettes.clear();
        this.sectionsOptimized.set(0);
        this.cowExpansions.set(0);
        this.estimatedBytesSaved.set(0);
    }

    public PaletteMetrics metrics() {
        return new PaletteMetrics(
            this.sharedPalettes.size(),
            this.sectionsOptimized.get(),
            this.cowExpansions.get(),
            Math.max(0L, this.estimatedBytesSaved.get())
        );
    }


    public interface SectionPalette {
        int get(int x, int y, int z);
        SectionPalette set(int x, int y, int z, int stateId);
        boolean isSingleState();
        int singleStateId();
        int size();
    }

    /**
     * Shared immutable single-state palette (0 bits overhead).
     */
    public static final class SharedSingleStatePalette implements SectionPalette {
        private final int stateId;
        private final AgcPaletteCowOptimizer optimizer;

        SharedSingleStatePalette(final int stateId, final AgcPaletteCowOptimizer optimizer) {
            this.stateId = stateId;
            this.optimizer = optimizer;
        }

        @Override
        public int get(final int x, final int y, final int z) {
            return this.stateId;
        }

        @Override
        public SectionPalette set(final int x, final int y, final int z, final int newStateId) {
            if (newStateId == this.stateId) {
                return this; // No mutation needed
            }
            if (this.optimizer != null) {
                this.optimizer.recordCowExpansion();
            }
            final MutableSectionPalette mutable = new MutableSectionPalette(this.stateId);
            return mutable.set(x, y, z, newStateId);
        }

        @Override
        public boolean isSingleState() {
            return true;
        }

        @Override
        public int singleStateId() {
            return this.stateId;
        }

        @Override
        public int size() {
            return 1;
        }
    }

    /**
     * Mutable section palette when heterogeneous block states are present.
     */
    public static final class MutableSectionPalette implements SectionPalette {
        private final int[] blockData = new int[4096];

        public MutableSectionPalette(final int fillStateId) {
            java.util.Arrays.fill(this.blockData, fillStateId);
        }

        public MutableSectionPalette(final int[] initialData) {
            System.arraycopy(initialData, 0, this.blockData, 0, Math.min(initialData.length, 4096));
        }

        @Override
        public int get(final int x, final int y, final int z) {
            final int index = (y << 8) | (z << 4) | x;
            return this.blockData[index & 4095];
        }

        @Override
        public SectionPalette set(final int x, final int y, final int z, final int stateId) {
            final int index = (y << 8) | (z << 4) | x;
            this.blockData[index & 4095] = stateId;
            return this;
        }

        @Override
        public boolean isSingleState() {
            return false;
        }

        @Override
        public int singleStateId() {
            return -1;
        }

        @Override
        public int size() {
            return 4096;
        }

        public int[] rawData() {
            return this.blockData;
        }
    }

    public record PaletteOptimizationResult(SectionPalette palette, boolean isShared) {}

    public record PaletteMetrics(
        int cachedSharedPalettes,
        long sectionsOptimized,
        long cowExpansions,
        long estimatedBytesSaved
    ) {}
}
