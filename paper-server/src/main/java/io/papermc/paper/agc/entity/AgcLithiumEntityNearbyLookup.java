package io.papermc.paper.agc.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Lithium Entity Retrieval & Section Search Accelerator.
 *
 * <p>Optimizes {@code EntitySectionStorage.forEachAccessibleNonEmptySection}:
 * replaces tree-set subSet allocations and iterator traversals with direct
 * coordinate-computed $O(1)$ section lookups for localized AABB queries.</p>
 */
public final class AgcLithiumEntityNearbyLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcLithiumEntityNearbyLookup.class);
    private static final AgcLithiumEntityNearbyLookup INSTANCE = new AgcLithiumEntityNearbyLookup();

    private final AtomicLong directLookups = new AtomicLong();
    private final AtomicLong sectionsScanned = new AtomicLong();

    public static AgcLithiumEntityNearbyLookup get() {
        return INSTANCE;
    }

    private AgcLithiumEntityNearbyLookup() {}

    /**
     * Executes direct coordinate-computed entity section search without tree-set subSet allocations.
     *
     * @return true if the search completed (either finished or was aborted); false if fallback should be used.
     */
    public <T extends EntityAccess> boolean searchDirect(
        final Long2ObjectMap<EntitySection<T>> sections,
        final int xMin, final int yMin, final int zMin,
        final int xMax, final int yMax, final int zMax,
        final AbortableIterationConsumer<EntitySection<T>> output
    ) {
        final int count = (xMax - xMin + 1) * (zMax - zMin + 1) * (yMax - yMin + 1);
        if (count > 64) {
            return false; // Fallback to tree iteration for giant bounds
        }

        this.directLookups.incrementAndGet();

        for (int x = xMin; x <= xMax; x++) {
            for (int z = zMin; z <= zMax; z++) {
                for (int y = yMin; y <= yMax; y++) {
                    final long key = SectionPos.asLong(x, y, z);
                    final EntitySection<T> section = sections.get(key);
                    if (section != null && !section.isEmpty() && section.getStatus().isAccessible()) {
                        this.sectionsScanned.incrementAndGet();
                        if (output.accept(section).shouldAbort()) {
                            return true;
                        }
                    }
                }
            }
        }

        return true;
    }

    public long getDirectLookups() {
        return this.directLookups.get();
    }

    public long getSectionsScanned() {
        return this.sectionsScanned.get();
    }
}
