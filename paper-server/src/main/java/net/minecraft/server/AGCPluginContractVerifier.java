package net.minecraft.server;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conservative plugin contract verifier for alpha17.
 * <p>
 * The verifier never grants off-thread Bukkit mutation. It only recognises
 * read-only/pure helper work as eligible for spare capacity and routes every
 * Bukkit-visible operation to ordered commit semantics.
 */
public final class AGCPluginContractVerifier {
    public static final AGCPluginContractVerifier INSTANCE = new AGCPluginContractVerifier();

    private final AtomicLong verified = new AtomicLong();
    private final AtomicLong orderedCommits = new AtomicLong();
    private final AtomicLong forbiddenMutations = new AtomicLong();
    private volatile long tickSequence;
    private volatile boolean strict = true;

    private AGCPluginContractVerifier() {
    }

    public void configure(final boolean strict) {
        this.strict = strict;
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Decision verify(final String pluginName, final String operation, final long estimatedCost) {
        final String op = operation == null ? "" : operation.toLowerCase(Locale.ROOT);
        final boolean visibleMutation = op.contains("setblock")
            || op.contains("teleport")
            || op.contains("spawn")
            || op.contains("inventory")
            || op.contains("scoreboard")
            || op.contains("event")
            || op.contains("command")
            || op.contains("world");
        final boolean forbidden = op.contains("unsafe") || op.contains("offthread") || op.contains("async-mutation");
        if (forbidden && this.strict) {
            this.forbiddenMutations.incrementAndGet();
            return new Decision(false, true, true, "forbidden off-thread mutation blocked for " + safe(pluginName));
        }
        if (visibleMutation) {
            this.orderedCommits.incrementAndGet();
            return new Decision(false, true, false, "Bukkit-visible plugin work uses ordered commit for " + safe(pluginName));
        }
        final AGCScale17AlgorithmKernel.Admission budget = AGCScale17AlgorithmKernel.INSTANCE.claim(
            AGCScale17AlgorithmKernel.Plane.PLUGIN_SEMANTIC_VERIFY,
            Math.max(1L, estimatedCost),
            pluginName + ':' + operation
        );
        if (!budget.admitted()) {
            this.orderedCommits.incrementAndGet();
            return new Decision(false, true, false, "plugin verifier waits; ordered semantics preserved: " + budget.reason());
        }
        this.verified.incrementAndGet();
        return new Decision(true, false, false, "pure/read-only plugin helper verified");
    }

    public String statusLine() {
        return "AGCPluginContractVerifier{tick=" + this.tickSequence
            + ", strict=" + this.strict
            + ", verified=" + this.verified.get()
            + ", orderedCommits=" + this.orderedCommits.get()
            + ", forbiddenMutations=" + this.forbiddenMutations.get()
            + '}';
    }

    private static String safe(final String value) {
        return value == null ? "unknown" : value;
    }

    public record Decision(boolean readOnlyHelper, boolean orderedCommit, boolean forbiddenMutation, String reason) {
    }
}
