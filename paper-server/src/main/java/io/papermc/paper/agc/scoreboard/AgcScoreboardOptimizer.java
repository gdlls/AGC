package io.papermc.paper.agc.scoreboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Differential Scoreboard & Objective Update Batcher.
 *
 * <p>Prevents network saturation caused by high-frequency scoreboard animations and dynamic sidebars:
 * <ul>
 *   <li><b>Change Detection:</b> Discards redundant score update packets when values remain unchanged.</li>
 *   <li><b>Per-Tick Coalescing:</b> Batches multiple objective/score mutations into a single network packet.</li>
 * </ul>
 * </p>
 */
public final class AgcScoreboardOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcScoreboardOptimizer.class);
    private static final AgcScoreboardOptimizer INSTANCE = new AgcScoreboardOptimizer();

    private final ConcurrentHashMap<String, Integer> lastKnownScores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ScoreboardEntry>> pendingBatchUpdates = new ConcurrentHashMap<>();

    private final AtomicLong scoreUpdatesSubmitted = new AtomicLong();
    private final AtomicLong redundantUpdatesFiltered = new AtomicLong();
    private final AtomicLong batchPacketsDispatched = new AtomicLong();

    public static AgcScoreboardOptimizer get() {
        return INSTANCE;
    }

    private AgcScoreboardOptimizer() {}

    public static final int MAX_ENTRIES = 16384;

    /**
     * Submits a score update and checks if it represents a meaningful change.
     *
     * @param objective Objective identifier
     * @param scoreHolder Score holder name/entry
     * @param newScore New score value
     * @return true if score changed and was queued, false if filtered as redundant
     */
    public boolean submitScoreUpdate(final String objective, final String scoreHolder, final int newScore) {
        if (objective == null || scoreHolder == null) return false;
        this.scoreUpdatesSubmitted.incrementAndGet();

        if (this.lastKnownScores.size() > MAX_ENTRIES) {
            this.lastKnownScores.clear();
        }

        final String key = objective + "#" + scoreHolder;
        final Integer previous = this.lastKnownScores.put(key, newScore);

        if (previous != null && previous == newScore) {
            this.redundantUpdatesFiltered.incrementAndGet();
            return false;
        }

        if (this.pendingBatchUpdates.size() > 1024) {
            this.pendingBatchUpdates.clear();
        }
        final var list = this.pendingBatchUpdates.computeIfAbsent(objective, k -> new ArrayList<>());
        synchronized (list) {
            if (list.size() < 4096) {
                list.add(new ScoreboardEntry(scoreHolder, newScore));
            }
        }
        return true;
    }

    /**
     * Invalidates a cached score when removed.
     */
    public void invalidateScore(final String objective, final String scoreHolder) {
        if (objective != null && scoreHolder != null) {
            this.lastKnownScores.remove(objective + "#" + scoreHolder);
        }
    }

    /**
     * Invalidates all cached scores for an objective.
     */
    public void invalidateObjective(final String objective) {
        if (objective != null) {
            this.pendingBatchUpdates.remove(objective);
            this.lastKnownScores.keySet().removeIf(k -> k.startsWith(objective + "#"));
        }
    }

    /**
     * Invalidates all cached scores for a player.
     */
    public void invalidatePlayer(final String scoreHolder) {
        if (scoreHolder != null) {
            this.lastKnownScores.keySet().removeIf(k -> k.endsWith("#" + scoreHolder));
        }
    }

    /**
     * Flushes pending batched updates for an objective.
     */
    public List<ScoreboardEntry> flushPendingUpdates(final String objective) {
        if (objective == null) return List.of();
        final var list = this.pendingBatchUpdates.remove(objective);
        if (list == null || list.isEmpty()) return List.of();

        this.batchPacketsDispatched.incrementAndGet();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public void clear() {
        this.lastKnownScores.clear();
        this.pendingBatchUpdates.clear();
        this.scoreUpdatesSubmitted.set(0);
        this.redundantUpdatesFiltered.set(0);
        this.batchPacketsDispatched.set(0);
    }

    public ScoreboardOptimizerMetrics metrics() {
        return new ScoreboardOptimizerMetrics(
            this.lastKnownScores.size(),
            this.pendingBatchUpdates.size(),
            this.scoreUpdatesSubmitted.get(),
            this.redundantUpdatesFiltered.get(),
            this.batchPacketsDispatched.get()
        );
    }

    public record ScoreboardEntry(String scoreHolder, int score) {}

    public record ScoreboardOptimizerMetrics(
        int trackedScores,
        int pendingObjectives,
        long scoreUpdatesSubmitted,
        long redundantUpdatesFiltered,
        long batchPacketsDispatched
    ) {
    }
}
