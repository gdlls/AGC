package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AGC — Container Slot Occupancy Bitmap (Lithium port).
 *
 * <p>Maintains a bitmask of non-empty slots for fast $O(1)$ container operations:</p>
 * <ul>
 *   <li>{@code isEmpty()}: $O(1)$ via {@code mask == 0L}</li>
 *   <li>{@code findFirstEmpty()}: $O(1)$ via {@code Long.numberOfTrailingZeros(~mask)}</li>
 *   <li>{@code findFirstOccupied()}: $O(1)$ via {@code Long.numberOfTrailingZeros(mask)}</li>
 * </ul>
 */
public final class AgcContainerSlotBitmap {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcContainerSlotBitmap.class);

    private long occupancyMask = 0L;
    private final int capacity;

    public AgcContainerSlotBitmap(final int capacity) {
        this.capacity = capacity;
    }

    public void setOccupied(final int slot, final boolean occupied) {
        if (slot >= 0 && slot < Math.min(64, this.capacity)) {
            if (occupied) {
                this.occupancyMask |= (1L << slot);
            } else {
                this.occupancyMask &= ~(1L << slot);
            }
        }
    }

    public boolean isEmpty() {
        return this.occupancyMask == 0L;
    }

    public boolean isFull() {
        if (this.capacity >= 64) {
            return this.occupancyMask == -1L;
        }
        final long fullMask = (1L << this.capacity) - 1L;
        return (this.occupancyMask & fullMask) == fullMask;
    }

    public int findFirstEmptySlot() {
        if (this.isFull()) {
            return -1;
        }
        final int index = Long.numberOfTrailingZeros(~this.occupancyMask);
        return index < this.capacity ? index : -1;
    }

    public int findFirstOccupiedSlot() {
        if (this.isEmpty()) {
            return -1;
        }
        final int index = Long.numberOfTrailingZeros(this.occupancyMask);
        return index < this.capacity ? index : -1;
    }

    public void clear() {
        this.occupancyMask = 0L;
    }
}
