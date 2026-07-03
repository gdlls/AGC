package net.minecraft.server;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny conservative semantic classifier cache for plugin-related work.
 * It is a classification cache only. It never grants permission for off-thread
 * Bukkit mutation. Known visible words are always ordered/forbidden.
 */
public final class AGCPluginSemanticJit {
    public static final AGCPluginSemanticJit INSTANCE = new AGCPluginSemanticJit();

    public enum Lane { READ_ONLY, PURE_COMPUTE, PACKET_PLAN, CHUNK_INTENT, ORDERED_COMMIT, FORBIDDEN_MUTATION }

    private final ConcurrentHashMap<String, Lane> cache = new ConcurrentHashMap<>();
    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCPluginSemanticJit() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        if (this.cache.size() > 8192) {
            this.cache.clear();
            this.lastReason = "semantic cache trimmed";
        } else {
            this.lastReason = "tick reset";
        }
    }

    public Decision classify(final String owner, final String operation, final boolean declaredReadOnly, final long cost) {
        this.requested.incrementAndGet();
        final String key = (safe(owner) + ':' + safe(operation) + ':' + declaredReadOnly).toLowerCase(Locale.ROOT);
        final Lane cached = this.cache.get(key);
        final Lane lane;
        if (cached != null) {
            this.hits.incrementAndGet();
            lane = cached;
        } else {
            lane = compute(operation, declaredReadOnly);
            this.cache.put(key, lane);
        }
        if (lane == Lane.FORBIDDEN_MUTATION || lane == Lane.ORDERED_COMMIT) {
            this.ordered.incrementAndGet();
            this.lastReason = "plugin semantic ordered: " + lane + " op=" + safe(operation);
            return new Decision(lane, false, lane == Lane.FORBIDDEN_MUTATION, true, this.lastReason);
        }
        final AGCScale19LogicKernel.Grant grant = AGCScale19LogicKernel.INSTANCE.claim(
            AGCScale19LogicKernel.Axis.PLUGIN_SEMANTIC_JIT,
            Math.max(1L, cost),
            "plugin-semantic-jit " + safe(operation)
        );
        if (!grant.admitted()) {
            this.ordered.incrementAndGet();
            this.lastReason = grant.reason();
            return new Decision(Lane.ORDERED_COMMIT, false, false, true, this.lastReason);
        }
        this.lastReason = "plugin semantic prepared: " + lane + " op=" + safe(operation);
        return new Decision(lane, true, false, false, this.lastReason);
    }

    public String statusLine() {
        return "AGCPluginSemanticJit{tick=" + this.tickSequence
            + ", requested=" + this.requested.get()
            + ", hits=" + this.hits.get()
            + ", ordered=" + this.ordered.get()
            + ", cache=" + this.cache.size()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static Lane compute(final String operation, final boolean declaredReadOnly) {
        final String op = safe(operation).toLowerCase(Locale.ROOT);
        if (op.contains("setblock") || op.contains("blockplace") || op.contains("blockbreak") || op.contains("spawn") || op.contains("teleport") || op.contains("inventory") || op.contains("scoreboard") || op.contains("command") || op.contains("event") || op.contains("world")) {
            return declaredReadOnly && (op.contains("plan") || op.contains("query") || op.contains("snapshot")) ? Lane.READ_ONLY : Lane.ORDERED_COMMIT;
        }
        if (op.contains("mutation") || op.contains("unsafe")) {
            return Lane.FORBIDDEN_MUTATION;
        }
        if (op.contains("packet")) return Lane.PACKET_PLAN;
        if (op.contains("chunk")) return Lane.CHUNK_INTENT;
        return declaredReadOnly ? Lane.READ_ONLY : Lane.PURE_COMPUTE;
    }

    private static String safe(final String value) {
        return value == null || value.isBlank() ? "unspecified" : value;
    }

    public record Decision(Lane lane, boolean prepared, boolean forbiddenMutation, boolean orderedCommit, String reason) {}
}
