package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lossless network send-graph compiler.
 * It groups only packet send planning metadata. It does not share mutable packet
 * instances, reorder a connection stream, or drop low priority packets.
 */
public final class AGCNetworkSendGraphCompiler {
    public static final AGCNetworkSendGraphCompiler INSTANCE = new AGCNetworkSendGraphCompiler();

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong compiled = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile int lastCohorts;
    private volatile String lastReason = "cold";

    private AGCNetworkSendGraphCompiler() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastCohorts = 0;
        this.lastReason = "tick reset";
    }

    public Plan compile(final String packetName, final int recipients, final boolean interactive, final long pendingDeferredFlushes) {
        this.requested.incrementAndGet();
        final int safeRecipients = Math.max(1, recipients);
        if (interactive) {
            this.ordered.incrementAndGet();
            this.lastReason = "interactive packet ordered: " + safe(packetName);
            return new Plan(false, 1, true, this.lastReason);
        }
        final int cohorts = Math.max(1, Math.min(256, (safeRecipients + 63) / 64));
        final AGCUnifiedTickPlanCompiler.Admission admission = AGCUnifiedTickPlanCompiler.INSTANCE.admit(
            AGCUnifiedTickPlanCompiler.Lane.NETWORK_FANOUT,
            Math.max(1L, cohorts + pendingDeferredFlushes / 512L),
            false,
            "network-send-graph " + safe(packetName)
        );
        this.lastCohorts = cohorts;
        if (!admission.readOnlyPrepare()) {
            this.ordered.incrementAndGet();
            this.lastReason = admission.reason();
            return new Plan(false, cohorts, true, admission.reason());
        }
        this.compiled.incrementAndGet();
        this.lastReason = "lossless send graph cohorts=" + cohorts;
        return new Plan(true, cohorts, false, this.lastReason);
    }

    public String statusLine() {
        return "AGCNetworkSendGraphCompiler{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", compiled=" + this.compiled.get()
            + ", ordered=" + this.ordered.get()
            + ", lastCohorts=" + this.lastCohorts
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String packetName) { return packetName == null || packetName.isBlank() ? "packet" : packetName; }
    public record Plan(boolean compiled, int cohorts, boolean orderedNow, String reason) {}
}
