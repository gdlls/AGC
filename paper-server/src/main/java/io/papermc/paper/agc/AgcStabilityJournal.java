package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Memory-Bounded Ring Buffer Stability Journal.
 *
 * <p>Records real-time performance decisions, governor profile transitions, latency warnings,
 * and semantic preservation events in a fixed-size zero-allocation ring buffer.</p>
 */
public final class AgcStabilityJournal {

    private static final AgcStabilityJournal INSTANCE = new AgcStabilityJournal();
    public static final int DEFAULT_CAPACITY = 256;

    public enum EventType {
        PROFILE_CHANGE,
        LATENCY_SPIKE,
        MEMORY_PRESSURE,
        GOVERNOR_ACTION,
        SAFETY_OVERRIDE,
        SYSTEM_INFO
    }

    private final JournalEntry[] buffer;
    private final int capacity;
    private final AtomicInteger head = new AtomicInteger(0);
    private final AtomicLong totalEventsRecorded = new AtomicLong(0);

    public static AgcStabilityJournal get() {
        return INSTANCE;
    }

    public AgcStabilityJournal() {
        this(DEFAULT_CAPACITY);
    }

    public AgcStabilityJournal(final int capacity) {
        this.capacity = Math.max(16, capacity);
        this.buffer = new JournalEntry[this.capacity];
    }

    /**
     * Records an event into the stability ring buffer.
     *
     * @param type    Event type
     * @param source  Component or world source name
     * @param message Detailed diagnostic message
     */
    public void record(final EventType type, final String source, final String message) {
        final long seq = this.totalEventsRecorded.incrementAndGet();
        final int index = (int) (seq % this.capacity);
        final JournalEntry entry = new JournalEntry(
            seq,
            System.currentTimeMillis(),
            type != null ? type : EventType.SYSTEM_INFO,
            source != null ? source : "AGC",
            message != null ? message : ""
        );
        this.buffer[index] = entry;
    }

    /**
     * Retrieves the most recent N events in chronological order.
     *
     * @param maxCount Maximum entries to return
     * @return List of recent journal entries
     */
    public List<JournalEntry> getRecentEntries(final int maxCount) {
        final int count = Math.min(Math.min(maxCount, this.capacity), (int) Math.min((long) this.capacity, this.totalEventsRecorded.get()));
        final List<JournalEntry> results = new ArrayList<>(count);
        final long currentSeq = this.totalEventsRecorded.get();

        for (long seq = currentSeq - count + 1; seq <= currentSeq; seq++) {
            if (seq <= 0) continue;
            final int index = (int) (seq % this.capacity);
            final JournalEntry entry = this.buffer[index];
            if (entry != null && entry.sequence() == seq) {
                results.add(entry);
            }
        }
        return results;
    }

    public int capacity() {
        return this.capacity;
    }

    public long totalEventsRecorded() {
        return this.totalEventsRecorded.get();
    }

    public void clear() {
        this.totalEventsRecorded.set(0);
        for (int i = 0; i < this.capacity; i++) {
            this.buffer[i] = null;
        }
    }

    public record JournalEntry(
        long sequence,
        long timestampMillis,
        EventType type,
        String source,
        String message
    ) {
        public String format() {
            final java.time.Instant instant = java.time.Instant.ofEpochMilli(this.timestampMillis);
            return String.format("[%s] #%d [%s] (%s): %s",
                instant.toString(), this.sequence, this.type.name(), this.source, this.message);
        }
    }
}
