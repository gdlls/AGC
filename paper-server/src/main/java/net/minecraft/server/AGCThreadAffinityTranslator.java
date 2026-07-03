package net.minecraft.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Explicit phase translator for plugin/gameplay work. This is the "translator"
 * layer requested for multi-threading: it classifies work before execution so
 * off-thread code can only do read-only preparation, while Bukkit-visible logic
 * is represented as an ordered commit ticket.
 */
public final class AGCThreadAffinityTranslator {
    public static final AGCThreadAffinityTranslator INSTANCE = new AGCThreadAffinityTranslator();

    public enum Phase {
        READ_ONLY_SNAPSHOT,
        PURE_COMPUTE_PLAN,
        DEADLINE_PACKET_PLAN,
        FIFO_CHUNK_INTENT,
        ORDERED_PRIMARY_COMMIT,
        FORBIDDEN_OFF_THREAD_MUTATION
    }

    private final AtomicLong translated = new AtomicLong();
    private final AtomicLong readOnly = new AtomicLong();
    private final AtomicLong orderedCommit = new AtomicLong();
    private final AtomicLong forbidden = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile long tickSequence;

    private AGCThreadAffinityTranslator() {
    }

    public void configure(final boolean enabled) {
        this.enabled = enabled;
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Translation translate(final Phase requested, final String owner, final String reason) {
        this.translated.incrementAndGet();
        if (!this.enabled) {
            this.orderedCommit.incrementAndGet();
            return new Translation(Phase.ORDERED_PRIMARY_COMMIT, true, "translator disabled; ordered primary commit");
        }
        final Phase granted = switch (requested) {
            case READ_ONLY_SNAPSHOT, PURE_COMPUTE_PLAN, DEADLINE_PACKET_PLAN, FIFO_CHUNK_INTENT -> requested;
            case ORDERED_PRIMARY_COMMIT -> Phase.ORDERED_PRIMARY_COMMIT;
            case FORBIDDEN_OFF_THREAD_MUTATION -> Phase.ORDERED_PRIMARY_COMMIT;
        };
        if (requested == Phase.FORBIDDEN_OFF_THREAD_MUTATION) {
            this.forbidden.incrementAndGet();
            AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.PLUGIN, AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT, "translated forbidden mutation for " + nullToEmpty(owner));
            return new Translation(granted, false, "off-thread mutation converted to ordered commit: " + nullToEmpty(reason));
        }
        if (granted == Phase.ORDERED_PRIMARY_COMMIT) {
            this.orderedCommit.incrementAndGet();
            AGCScaleKernel.INSTANCE.claim(AGCScaleKernel.Axis.PLUGIN_TRANSLATION, 1L, reason);
        } else {
            this.readOnly.incrementAndGet();
        }
        return new Translation(granted, true, nullToEmpty(reason));
    }

    public String statusLine() {
        return "AGCThreadAffinityTranslator{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", translated=" + this.translated.get()
            + ", readOnly=" + this.readOnly.get()
            + ", orderedCommit=" + this.orderedCommit.get()
            + ", forbiddenConverted=" + this.forbidden.get()
            + '}';
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    public record Translation(Phase phase, boolean direct, String reason) {
    }
}
