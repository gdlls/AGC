package io.papermc.paper.agc.worldgen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcStructureNbtPrunerTest {

    @BeforeEach
    public void setup() {
        AgcStructureNbtPruner.get().clear();
    }

    @Test
    public void testBoundsFilterKeepsOnlyInside() {
        final int[] xs = {0, 5, 16, 31, 100};
        final int[] ys = {64, 64, 64, 64, 64};
        final int[] zs = {0, 5, 16, 31, 100};
        // Chunk 0,0 spans 0..15.
        final int[] kept = AgcStructureNbtPruner.get().filterIndices(
            xs, ys, zs, 5, 0, 0, 0, 15, 255, 15, false);
        assertArrayEquals(new int[] {0, 1}, kept);
        assertEquals(5L, AgcStructureNbtPruner.get().metrics().positionsScanned());
        assertEquals(2L, AgcStructureNbtPruner.get().metrics().positionsKept());
    }

    @Test
    public void testInclusiveBoundsEdges() {
        final int[] xs = {15, 16};
        final int[] ys = {0, 0};
        final int[] zs = {15, 16};
        final int[] kept = AgcStructureNbtPruner.get().filterIndices(
            xs, ys, zs, 2, 0, 0, 0, 15, 255, 15, false);
        assertArrayEquals(new int[] {0}, kept);
    }

    @Test
    public void testFinalizeProcessingBypass() {
        final int[] xs = {999};
        final int[] ys = {999};
        final int[] zs = {999};
        final int[] kept = AgcStructureNbtPruner.get().filterIndices(
            xs, ys, zs, 1, 0, 0, 0, 15, 255, 15, true);
        assertArrayEquals(new int[] {0}, kept);
        assertEquals(1L, AgcStructureNbtPruner.get().metrics().bypassedFinalize());
    }

    @Test
    public void testEntryFilter() {
        final var entries = java.util.List.of("a", "b", "c", "d");
        final var kept = AgcStructureNbtPruner.get().filterEntries(entries, i -> i % 2 == 0);
        assertEquals(java.util.List.of("a", "c"), kept);
        assertTrue(AgcStructureNbtPruner.get().metrics().pruneCalls() >= 1);
    }
}
