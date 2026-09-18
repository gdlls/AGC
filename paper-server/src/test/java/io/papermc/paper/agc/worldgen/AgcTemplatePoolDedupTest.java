package io.papermc.paper.agc.worldgen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcTemplatePoolDedupTest {

    @BeforeEach
    public void setup() {
        AgcTemplatePoolDedup.get().clear();
    }

    @Test
    public void testDuplicateFailuresSkippedSameResult() {
        final String house = "house";
        final String tower = "tower";
        // Weight-expanded shuffled list: house x3 (all fail), tower x1 (fits).
        final List<String> shuffled = List.of(house, house, tower, house);
        final AtomicInteger checks = new AtomicInteger();

        final int selected = AgcTemplatePoolDedup.get().selectFirstFit(shuffled, candidate -> {
            checks.incrementAndGet();
            return candidate.equals(tower);
        });

        assertEquals(2, selected);
        // house checked once, tower checked once — the 2nd house skipped
        // (4th slot never reached: tower already selected).
        assertEquals(2, checks.get());
        assertEquals(1L, AgcTemplatePoolDedup.get().metrics().duplicateSkips());
    }

    @Test
    public void testNoFitReturnsMinusOne() {
        final String dup = "dup";
        final List<String> shuffled = List.of(dup, dup, dup);
        final int selected = AgcTemplatePoolDedup.get().selectFirstFit(shuffled, c -> false);
        assertEquals(-1, selected);
        assertEquals(2L, AgcTemplatePoolDedup.get().metrics().duplicateSkips());
    }

    @Test
    public void testDistinctElementsAllChecked() {
        final List<String> shuffled = new ArrayList<>(List.of("a", "b", "c"));
        final int selected = AgcTemplatePoolDedup.get().selectFirstFit(shuffled, "c"::equals);
        assertEquals(2, selected);
        assertEquals(0L, AgcTemplatePoolDedup.get().metrics().duplicateSkips());
        assertEquals(3L, AgcTemplatePoolDedup.get().metrics().candidateChecks());
    }

    @Test
    public void testDeduplicatedViewDocumentsMultiplicity() {
        final String house = "house";
        final List<String> shuffled = List.of(house, "tower", house, house);
        final var view = AgcTemplatePoolDedup.get().deduplicatedView(shuffled);
        assertEquals(2, view.size());
        assertEquals(3, view.get(0).multiplicity());
        assertEquals(1, view.get(1).multiplicity());
        assertTrue(AgcTemplatePoolDedup.get().metrics().candidateChecks() == 0L);
    }
}
