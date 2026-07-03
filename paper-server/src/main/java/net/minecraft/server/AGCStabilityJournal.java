package net.minecraft.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small in-memory operator journal for AGC runtime safety decisions.
 * <p>
 * It is intentionally process-local and bounded: the journal is for live
 * diagnostics such as profile changes, semantic-preservation events and rollbacks, not for
 * replacing the normal server log.
 */
public final class AGCStabilityJournal {
    public static final AGCStabilityJournal INSTANCE = new AGCStabilityJournal();

    private static final int CAPACITY = 256;

    private final Entry[] ring = new Entry[CAPACITY];
    private final AtomicLong sequence = new AtomicLong();

    private AGCStabilityJournal() {
    }

    public void record(final String subsystem, final String message) {
        final long id = this.sequence.incrementAndGet();
        final Entry entry = new Entry(
            id,
            System.currentTimeMillis(),
            normalise(subsystem, "agc"),
            normalise(message, "unspecified")
        );
        synchronized (this.ring) {
            this.ring[(int) (id % CAPACITY)] = entry;
        }
    }

    public List<Entry> snapshot(final int maxEntries) {
        final int limit = Math.max(0, Math.min(CAPACITY, maxEntries));
        final long latest = this.sequence.get();
        final ArrayList<Entry> entries = new ArrayList<>(limit);
        synchronized (this.ring) {
            for (long id = Math.max(1L, latest - limit + 1L); id <= latest; id++) {
                final Entry entry = this.ring[(int) (id % CAPACITY)];
                if (entry != null && entry.sequence() == id) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    public String dump(final int maxEntries) {
        final List<Entry> entries = this.snapshot(maxEntries);
        if (entries.isEmpty()) {
            return "AGC stability journal: empty";
        }
        final StringBuilder builder = new StringBuilder(entries.size() * 96);
        builder.append("AGC stability journal (last ").append(entries.size()).append("):");
        for (final Entry entry : entries) {
            builder.append('\n')
                .append('#').append(entry.sequence())
                .append(' ')
                .append(Instant.ofEpochMilli(entry.epochMillis()))
                .append(' ')
                .append(entry.subsystem())
                .append(" - ")
                .append(entry.message());
        }
        return builder.toString();
    }

    public String statusLine() {
        final long latest = this.sequence.get();
        return "AGCStabilityJournal{events=" + latest + ", capacity=" + CAPACITY + '}';
    }

    private static String normalise(final String value, final String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    public record Entry(long sequence, long epochMillis, String subsystem, String message) {
    }
}
