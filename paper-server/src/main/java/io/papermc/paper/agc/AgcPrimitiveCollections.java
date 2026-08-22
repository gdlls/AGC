package io.papermc.paper.agc;

import java.util.Arrays;

/**
 * AGC — Zero-Allocation Primitive Collections & Packing Utilities.
 *
 * <p>Avoids Java object autoboxing ({@link Long}, {@link Integer}) on hot-path operations
 * such as chunk coordinate lookups, entity ID mappings, and block position packing.</p>
 */
public final class AgcPrimitiveCollections {

    private AgcPrimitiveCollections() {}

    // =========================================================================
    // Chunk & Block coordinate bit packing (Fast bitwise arithmetic)
    // =========================================================================

    /**
     * Packs (chunkX, chunkZ) into a single 64-bit primitive long.
     */
    public static long packChunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int unpackChunkX(final long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    public static int unpackChunkZ(final long chunkKey) {
        return (int) chunkKey;
    }

    /**
     * Packs (x, y, z) block coordinates into a single 64-bit primitive long (26 bits X, 12 bits Y, 26 bits Z).
     */
    public static long packBlockPos(final int x, final int y, final int z) {
        return (((long) (x & 0x3FFFFFF)) << 38) | (((long) (y & 0xFFF)) << 26) | (long) (z & 0x3FFFFFF);
    }

    public static int unpackBlockX(final long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackBlockY(final long packed) {
        return (int) ((packed << 26) >> 52);
    }

    public static int unpackBlockZ(final long packed) {
        return (int) ((packed << 38) >> 38);
    }

    // =========================================================================
    // Primitive IntArrayList (Zero-boxing resizable primitive integer list)
    // =========================================================================

    public static final class IntArrayList {
        private int[] elements;
        private int size;

        public IntArrayList() {
            this(16);
        }

        public IntArrayList(final int initialCapacity) {
            this.elements = new int[Math.max(4, initialCapacity)];
            this.size = 0;
        }

        public void add(final int value) {
            if (this.size == this.elements.length) {
                this.elements = Arrays.copyOf(this.elements, this.elements.length << 1);
            }
            this.elements[this.size++] = value;
        }

        public int get(final int index) {
            if (index < 0 || index >= this.size) {
                throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + this.size);
            }
            return this.elements[index];
        }

        public int size() {
            return this.size;
        }

        public boolean isEmpty() {
            return this.size == 0;
        }

        public void clear() {
            this.size = 0;
        }

        public int[] toArray() {
            return Arrays.copyOf(this.elements, this.size);
        }
    }
}
