package net.minecraft.server;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conservative plugin semantic classifier used by the thread translators.
 * It recognises common plugin-visible mutation words and routes them to ordered
 * commit while allowing pure/read-only helper work to consume spare capacity.
 */
public final class AGCPluginSemanticModel {
    public static final AGCPluginSemanticModel INSTANCE = new AGCPluginSemanticModel();

    public enum Classifier {
        READ_ONLY,
        PURE_COMPUTE,
        PACKET_PLAN,
        CHUNK_INTENT,
        ORDERED_COMMIT,
        FORBIDDEN_MUTATION
    }

    private final AtomicLong readOnly = new AtomicLong();
    private final AtomicLong pureCompute = new AtomicLong();
    private final AtomicLong orderedCommit = new AtomicLong();
    private final AtomicLong forbidden = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private volatile long tickSequence;

    private AGCPluginSemanticModel() {
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Decision classify(final String owner, final String operation) {
        final String text = ((owner == null ? "" : owner) + ' ' + (operation == null ? "" : operation)).toLowerCase(Locale.ROOT);
        final Classifier classifier;
        if (text.contains("setblock") || text.contains("teleport") || text.contains("inventory") || text.contains("scoreboard") || text.contains("event") || text.contains("spawn")) {
            classifier = Classifier.ORDERED_COMMIT;
        } else if (text.contains("unsafe") || text.contains("offthread") || text.contains("mutation")) {
            classifier = Classifier.FORBIDDEN_MUTATION;
        } else if (text.contains("packet")) {
            classifier = Classifier.PACKET_PLAN;
        } else if (text.contains("chunk")) {
            classifier = Classifier.CHUNK_INTENT;
        } else if (text.contains("read") || text.contains("snapshot") || text.contains("query")) {
            classifier = Classifier.READ_ONLY;
        } else {
            classifier = Classifier.PURE_COMPUTE;
        }
        if (classifier == Classifier.FORBIDDEN_MUTATION) {
            this.forbidden.incrementAndGet();
            return new Decision(false, true, classifier, "forbidden off-thread mutation remains blocked");
        }
        if (classifier == Classifier.ORDERED_COMMIT) {
            this.orderedCommit.incrementAndGet();
            return new Decision(false, true, classifier, "plugin-visible operation routed to ordered commit");
        }
        final AGCScale16ControlLaw.Admission budget = AGCScale16ControlLaw.INSTANCE.claim(AGCScale16ControlLaw.Axis.PLUGIN_SEMANTICS, 1L, operation);
        if (!budget.admitted()) {
            this.waits.incrementAndGet();
            return new Decision(false, true, classifier, budget.reason());
        }
        if (classifier == Classifier.READ_ONLY) {
            this.readOnly.incrementAndGet();
        } else {
            this.pureCompute.incrementAndGet();
        }
        return new Decision(true, false, classifier, "plugin helper classified as semantic-safe " + classifier);
    }

    public String statusLine() {
        return "AGCPluginSemanticModel{tick=" + this.tickSequence
            + ", readOnly=" + this.readOnly.get()
            + ", pureCompute=" + this.pureCompute.get()
            + ", orderedCommit=" + this.orderedCommit.get()
            + ", forbidden=" + this.forbidden.get()
            + ", waits=" + this.waits.get()
            + '}';
    }

    public record Decision(boolean concurrentPrepare, boolean orderedCommit, Classifier classifier, String reason) {
    }
}
