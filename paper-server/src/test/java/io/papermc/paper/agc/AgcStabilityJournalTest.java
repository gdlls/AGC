package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcStabilityJournal}.
 */
class AgcStabilityJournalTest {

    @BeforeEach
    @AfterEach
    void resetJournal() {
        AgcStabilityJournal.get().clear();
    }

    @Test
    void recordAndRetrieveEventsInChronologicalOrder() {
        final var journal = new AgcStabilityJournal(10);
        journal.record(AgcStabilityJournal.EventType.PROFILE_CHANGE, "Governor", "State switched to HEALTHY");
        journal.record(AgcStabilityJournal.EventType.LATENCY_SPIKE, "World-Tick", "MSPT spike: 42.1ms");

        assertEquals(2, journal.totalEventsRecorded());

        final List<AgcStabilityJournal.JournalEntry> entries = journal.getRecentEntries(10);
        assertEquals(2, entries.size());
        assertEquals(1, entries.get(0).sequence());
        assertEquals(AgcStabilityJournal.EventType.PROFILE_CHANGE, entries.get(0).type());
        assertEquals("Governor", entries.get(0).source());

        assertEquals(2, entries.get(1).sequence());
        assertEquals(AgcStabilityJournal.EventType.LATENCY_SPIKE, entries.get(1).type());
    }

    @Test
    void ringBufferWrapsAroundCorrectlyWithoutOverflow() {
        final var journal = new AgcStabilityJournal(4);

        for (int i = 1; i <= 10; i++) {
            journal.record(AgcStabilityJournal.EventType.GOVERNOR_ACTION, "Test", "Event " + i);
        }

        assertEquals(10, journal.totalEventsRecorded());

        // Capacity is 4 -> should return the most recent 4 events (events 7, 8, 9, 10)
        final List<AgcStabilityJournal.JournalEntry> entries = journal.getRecentEntries(4);
        assertEquals(4, entries.size());
        assertEquals(7, entries.get(0).sequence());
        assertEquals(8, entries.get(1).sequence());
        assertEquals(9, entries.get(2).sequence());
        assertEquals(10, entries.get(3).sequence());
    }

    @Test
    void entryFormattingContainsAllFields() {
        final var entry = new AgcStabilityJournal.JournalEntry(
            1,
            System.currentTimeMillis(),
            AgcStabilityJournal.EventType.SAFETY_OVERRIDE,
            "Engine",
            "Disabled experimental parallel tick"
        );

        final String formatted = entry.format();
        assertTrue(formatted.contains("#1"));
        assertTrue(formatted.contains("SAFETY_OVERRIDE"));
        assertTrue(formatted.contains("Engine"));
        assertTrue(formatted.contains("Disabled experimental parallel tick"));
    }
}
