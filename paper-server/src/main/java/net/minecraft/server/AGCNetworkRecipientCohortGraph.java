package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lossless recipient cohort graph for network fanout.
 * <p>
 * It groups equivalent read-only recipient planning shapes; it does not share
 * mutable packets, does not drop packets and does not reorder any connection's
 * packet stream.
 */
public final class AGCNetworkRecipientCohortGraph {
    public static final AGCNetworkRecipientCohortGraph INSTANCE = new AGCNetworkRecipientCohortGraph();

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private final AtomicLong cohorts = new AtomicLong();
    private volatile long tickSequence;
    private volatile int targetRecipientsPerCohort = 48;
    private volatile int maxCohortsPerPacket = 4096;

    private AGCNetworkRecipientCohortGraph() {
    }

    public void configure(final int targetRecipientsPerCohort, final int maxCohortsPerPacket) {
        this.targetRecipientsPerCohort = Math.max(8, Math.min(512, targetRecipientsPerCohort));
        this.maxCohortsPerPacket = Math.max(16, Math.min(65_536, maxCohortsPerPacket));
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Plan plan(final String packetName, final boolean interactive, final long estimatedRecipients, final int protocolVariants) {
        if (interactive) {
            return new Plan(false, 0L, "interactive packets stay immediate ordered");
        }
        final long recipients = Math.max(1L, estimatedRecipients);
        final int variants = Math.max(1, protocolVariants);
        final long rawCohorts = Math.max(1L, (recipients + this.targetRecipientsPerCohort - 1L) / this.targetRecipientsPerCohort) * variants;
        final long boundedCohorts = Math.min(this.maxCohortsPerPacket, rawCohorts);
        final long cost = Math.max(1L, boundedCohorts + recipients / 64L);
        final AGCScale17AlgorithmKernel.Admission admission = AGCScale17AlgorithmKernel.INSTANCE.claim(
            AGCScale17AlgorithmKernel.Plane.NETWORK_RECIPIENT_COHORT,
            cost,
            packetName
        );
        if (!admission.admitted()) {
            this.waits.incrementAndGet();
            return new Plan(false, boundedCohorts, "recipient cohort waits; connection order remains unchanged: " + admission.reason());
        }
        this.planned.incrementAndGet();
        this.cohorts.addAndGet(boundedCohorts);
        return new Plan(true, boundedCohorts, "lossless recipient cohorts");
    }

    public String statusLine() {
        return "AGCNetworkRecipientCohortGraph{tick=" + this.tickSequence
            + ", targetRecipientsPerCohort=" + this.targetRecipientsPerCohort
            + ", maxCohortsPerPacket=" + this.maxCohortsPerPacket
            + ", planned=" + this.planned.get()
            + ", waits=" + this.waits.get()
            + ", cohorts=" + this.cohorts.get()
            + '}';
    }

    public record Plan(boolean admitted, long cohorts, String reason) {
    }
}
