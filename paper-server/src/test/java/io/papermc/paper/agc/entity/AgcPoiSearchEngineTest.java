package io.papermc.paper.agc.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcPoiSearchEngineTest {

    private static final int gathering = 17;
    private static final int home = 3;

    @BeforeEach
    public void setup() {
        AgcPoiSearchEngine.get().clear();
    }

    @Test
    public void testAddQueryRemove() {
        final var engine = AgcPoiSearchEngine.get();
        engine.addPoi(8, 64, 8, home);
        engine.addPoi(40, 64, 8, home);
        engine.addPoi(8, 64, 8, gathering);

        final List<Long> found = engine.queryRadius(8, 64, 8, 48, home, null);
        assertEquals(2, found.size());
        // Nearest first.
        assertEquals(8, AgcPoiSearchEngine.unpackX(found.get(0)));
        assertEquals(40, AgcPoiSearchEngine.unpackX(found.get(1)));

        // Type filter respected.
        assertEquals(1, engine.queryRadius(8, 64, 8, 48, gathering, null).size());

        engine.removePoi(40, 64, 8);
        assertEquals(1, engine.queryRadius(8, 64, 8, 48, home, null).size());
        assertTrue(engine.metrics().sectionRetrievals() >= 1);
    }

    @Test
    public void testRadiusBoundaryParity() {
        final var engine = AgcPoiSearchEngine.get();
        engine.addPoi(13, 64, 8, home); // exactly 5 away on X
        engine.addPoi(14, 64, 8, home); // 6 away
        final List<Long> found = engine.queryRadius(8, 64, 8, 5, home, null);
        assertEquals(1, found.size());
    }

    @Test
    public void testPortalLoadedCacheFastPath() {
        final var engine = AgcPoiSearchEngine.get();
        engine.addPoi(8, 64, 8, home);
        // First query populates the loaded cache as unloaded...
        assertTrue(engine.queryRadius(8, 64, 8, 48, home, section -> false).isEmpty());
        final long retrievalsAfterFirst = engine.metrics().sectionRetrievals();
        // ...second query hits the cache and performs no section retrievals.
        assertTrue(engine.queryRadius(8, 64, 8, 48, home, section -> {
            throw new AssertionError("must not probe when cached");
        }).isEmpty());
        assertEquals(retrievalsAfterFirst, engine.metrics().sectionRetrievals());
        assertTrue(engine.metrics().portalCacheHits() >= 1);
    }

    @Test
    public void testRaidSecondarySkip() {
        final var engine = AgcPoiSearchEngine.get();
        assertTrue(engine.shouldSkipRaidSecondarySearch(false));
        assertFalse(engine.shouldSkipRaidSecondarySearch(true));
        assertEquals(1L, engine.metrics().raidSecondarySkips());
    }

    @Test
    public void testPackRoundTrip() {
        final int x = -12345;
        final int y = 310;
        final int z = 67890;
        final long packed = AgcPoiSearchEngine.packPos(x, y + 524288, z + 524288);
        assertEquals(x, AgcPoiSearchEngine.unpackX(packed));
        assertEquals(y, AgcPoiSearchEngine.unpackY(packed));
        assertEquals(z, AgcPoiSearchEngine.unpackZ(packed));
        assertFalse(AgcPoiSearchEngine.packSection(1, 2, 3) == AgcPoiSearchEngine.packSection(1, 2, 4));
    }
}
