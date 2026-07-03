package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/** Lossless broadcast planner for high-recipient packet fanout. */
public final class AGCNetworkBroadcastPlanner {
    public static final AGCNetworkBroadcastPlanner INSTANCE = new AGCNetworkBroadcastPlanner();

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong deadlinePlanned = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCNetworkBroadcastPlanner() {
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "reset";
    }

    public Plan plan(final String packetName, final long recipients, final boolean interactive, final int deadlineTicks) {
        this.planned.incrementAndGet();
        final long safeRecipients = Math.max(1L, recipients);
        final int cohorts = (int) Math.max(1L, Math.min(8192L, (safeRecipients + 31L) / 32L));
        final int graphEdges = (int) Math.max(cohorts, Math.min(1_048_576L, safeRecipients + cohorts * 2L));
        final boolean orderedNow = interactive || deadlineTicks <= 0;
        if (!orderedNow) {
            this.deadlinePlanned.incrementAndGet();
        }
        this.lastReason = (orderedNow ? "ordered" : "deadline") + " broadcast packet=" + safe(packetName) + " recipients=" + safeRecipients + " cohorts=" + cohorts + " edges=" + graphEdges;
        return new Plan(!orderedNow, orderedNow, cohorts, graphEdges, this.lastReason);
    }

    public String statusLine() {
        return "AGCNetworkBroadcastPlanner{tick=" + this.tickSequence
            + ", planned=" + this.planned.get()
            + ", deadlinePlanned=" + this.deadlinePlanned.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public record Plan(boolean admitted, boolean orderedNow, int cohorts, int graphEdges, String reason) {}
}
