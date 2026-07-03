package net.minecraft.server;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conservative plugin determinism guard.
 *
 * Repeated plugin operations can be classified cheaply, but Bukkit-visible
 * mutations are always routed to ordered primary-thread commit semantics.
 */
public final class AGCPluginDeterminismGuard {
    public static final AGCPluginDeterminismGuard INSTANCE = new AGCPluginDeterminismGuard();

    public enum Classification {
        READ_ONLY,
        PURE_COMPUTE,
        PACKET_PLAN,
        CHUNK_INTENT,
        ORDERED_COMMIT,
        FORBIDDEN_MUTATION
    }

    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong readOnly = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private final AtomicLong forbidden = new AtomicLong();
    private volatile long tickSequence;
    private volatile Classification lastClassification = Classification.ORDERED_COMMIT;
    private volatile String lastReason = "cold";

    private AGCPluginDeterminismGuard() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "tick reset";
    }

    public Decision classify(final String pluginName, final String operation, final boolean declaredReadOnly, final boolean primaryThreadOnly) {
        this.requested.incrementAndGet();
        final String op = operation == null ? "" : operation.toLowerCase(Locale.ROOT);
        final Classification classification;
        if (containsAny(op, "setblock", "teleport", "spawn", "inventory", "scoreboard", "command", "event", "world", "blockplace", "blockbreak", "damage", "interact")) {
            classification = primaryThreadOnly ? Classification.ORDERED_COMMIT : Classification.FORBIDDEN_MUTATION;
        } else if (containsAny(op, "packet", "cosmetic", "title", "bossbar")) {
            classification = Classification.PACKET_PLAN;
        } else if (containsAny(op, "chunk", "preload", "lookahead")) {
            classification = Classification.CHUNK_INTENT;
        } else if (declaredReadOnly || containsAny(op, "query", "snapshot", "compute", "path", "plan")) {
            classification = declaredReadOnly ? Classification.READ_ONLY : Classification.PURE_COMPUTE;
        } else {
            classification = Classification.ORDERED_COMMIT;
        }
        this.lastClassification = classification;
        if (classification == Classification.FORBIDDEN_MUTATION) {
            this.forbidden.incrementAndGet();
            this.lastReason = "forbidden off-thread mutation plugin=" + safe(pluginName) + " op=" + safe(operation);
            return new Decision(classification, false, true, this.lastReason);
        }
        if (classification == Classification.ORDERED_COMMIT) {
            this.ordered.incrementAndGet();
            this.lastReason = "ordered plugin commit plugin=" + safe(pluginName) + " op=" + safe(operation);
            return new Decision(classification, true, false, this.lastReason);
        }
        final AGCScale20LosslessPipeline.Grant grant = AGCScale20LosslessPipeline.INSTANCE.claim(
            AGCScale20LosslessPipeline.Stage.PLUGIN_TRANSLATION,
            classification == Classification.READ_ONLY ? 1L : 4L,
            "plugin-determinism " + safe(pluginName) + ":" + safe(operation)
        );
        if (!grant.admitted()) {
            this.ordered.incrementAndGet();
            this.lastReason = grant.reason();
            return new Decision(Classification.ORDERED_COMMIT, true, false, this.lastReason);
        }
        this.readOnly.incrementAndGet();
        this.lastReason = "classified lossless plugin helper " + classification + " plugin=" + safe(pluginName);
        return new Decision(classification, false, false, this.lastReason);
    }

    public String statusLine() {
        return "AGCPluginDeterminismGuard{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", readOnly=" + this.readOnly.get()
            + ", ordered=" + this.ordered.get()
            + ", forbidden=" + this.forbidden.get()
            + ", lastClassification=" + this.lastClassification
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static boolean containsAny(final String value, final String... needles) {
        for (final String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(final String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public record Decision(Classification classification, boolean orderedCommit, boolean forbiddenMutation, String reason) {}
}
