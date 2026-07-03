package net.minecraft.server;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conservative plugin semantic firewall for alpha18.
 * It converts ambiguous plugin helper work into ordered commit unless it can be
 * proven read-only/pure-compute by contract and operation shape.
 */
public final class AGCPluginSemanticFirewall {
    public static final AGCPluginSemanticFirewall INSTANCE = new AGCPluginSemanticFirewall();

    private final AtomicLong classified = new AtomicLong();
    private final AtomicLong readOnly = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private final AtomicLong forbidden = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCPluginSemanticFirewall() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastReason = "tick reset";
    }

    public Decision classify(final String pluginName, final String operation, final boolean offThread, final long estimatedCost) {
        this.classified.incrementAndGet();
        final String op = operation == null ? "" : operation.toLowerCase(Locale.ROOT);
        final boolean mutation = op.contains("setblock") || op.contains("teleport") || op.contains("spawn")
            || op.contains("inventory") || op.contains("scoreboard") || op.contains("event") || op.contains("command")
            || op.contains("world") || op.contains("entitydamage") || op.contains("blockplace") || op.contains("blockbreak");
        if (offThread && mutation) {
            this.forbidden.incrementAndGet();
            this.lastReason = "forbidden off-thread Bukkit mutation: " + safe(pluginName) + ':' + safe(operation);
            return new Decision(false, true, true, this.lastReason);
        }
        if (mutation) {
            this.ordered.incrementAndGet();
            this.lastReason = "ordered primary-thread commit required: " + safe(operation);
            return new Decision(false, true, false, this.lastReason);
        }
        final AGCReadOnlyWorkStealingPlanner.Admission worker = AGCReadOnlyWorkStealingPlanner.INSTANCE.claim(Math.max(1L, estimatedCost), "plugin " + safe(pluginName) + ' ' + safe(operation));
        if (!worker.admitted()) {
            this.ordered.incrementAndGet();
            this.lastReason = worker.reason();
            return new Decision(false, true, false, worker.reason());
        }
        this.readOnly.incrementAndGet();
        this.lastReason = "read-only/pure compute plugin helper: " + safe(pluginName) + ':' + safe(operation);
        return new Decision(true, false, false, this.lastReason);
    }

    public String statusLine() {
        return "AGCPluginSemanticFirewall{tick=" + this.tickSequence
            + ", classified=" + this.classified.get()
            + ", readOnly=" + this.readOnly.get()
            + ", ordered=" + this.ordered.get()
            + ", forbidden=" + this.forbidden.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static String safe(final String value) { return value == null || value.isBlank() ? "unknown" : value; }
    public record Decision(boolean readOnlyPrepare, boolean orderedCommit, boolean forbiddenMutation, String reason) {}
}
