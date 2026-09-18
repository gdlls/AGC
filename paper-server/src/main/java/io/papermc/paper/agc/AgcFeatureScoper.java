package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * AGC — in-process paired A/B measurement of individual capability features.
 *
 * <h2>The problem this exists to solve</h2>
 * <p>Paired A/B scoping measurement eliminates measurement distortion caused by three effects:</p>
 * <ul>
 *   <li><b>JVM/world warm-up.</b> The same build measures a 5x spread in p99 MSPT between a cold and a warm
 *       server, so a before/after pair taken with a restart in between measures the restart, not the change.</li>
 *   <li><b>World drift.</b> The benchmark world accumulates entities across sessions, so absolute numbers
 *       from two different sessions are not comparable at all.</li>
 *   <li><b>One-shot sampling.</b> A single run gives no estimate of spread, so noise is indistinguishable
 *       from signal.</li>
 * </ul>
 *
 * <h2>The instrument</h2>
 * <p>A scoping run measures <b>one</b> feature on <b>one live server, in one JVM, with no restart and no
 * rebuild</b> — which removes all three effects above. The feature is flipped with the ordinary runtime
 * override ({@link AgcCapabilityMatrix#setRuntimeOverride}) so the code path measured is exactly the one an
 * operator would get. The schedule is <b>ABBA</b> (OFF, ON, ON, OFF) per pair, and consecutive pairs are
 * chained, so a monotonic drift over the run lands wherever it lands but <b>cancels out of the arm
 * medians</b>: OFF windows occupy the outside positions and ON windows the inside ones.</p>
 *
 * <p>The verdict is conservative and explainable. It is computed from <b>paired</b> effects, never from the
 * raw arm spread — comparing the spread of all OFF windows against the spread of all ON windows would
 * fold the run's own drift into the noise estimate and could not see a real effect through it (this was
 * caught by {@code abbaScheduleCancelsMonotonicDrift}):</p>
 * <pre>
 *  pairEffect[i] = mean(ON windows of pair i) - mean(OFF windows of pair i)   // ABBA balances drift here
 *  delta         = median(pairEffect)                                        // the drift-corrected effect
 *  noise         = spread(pairEffect)                                        // is the effect reproducible?
 *  threshold     = max(noise, load * MIN_RELATIVE_EFFECT)                     // + a meaningfulness floor
 *  |delta| &lt;= threshold  ->  NOISE
 *  delta &lt; -threshold     ->  HELPS   (ON is faster)
 *  delta &gt;  threshold     ->  HURTS
 * </pre>
 * <p>The within-pair balance is the whole point of ABBA: the two OFF windows sit at the outside positions
 * and the two ON windows at the inside ones, so their window-index means are equal and any linear drift
 * cancels out of {@code pairEffect} exactly. The relative floor keeps a single-pair run from certifying a
 * sub-basis-point change.</p>
 *
 * <p>If the server was not actually working during the run (median MSPT below {@link #MIN_LOAD_MSPT}) the
 * result is {@link Verdict#INCONCLUSIVE_IDLE} rather than a verdict — an idle tick loop has no measurable
 * cost to remove, which is the failure mode that makes most of the numbers in this repository
 * meaningless.</p>
 *
 * <h2>Preconditions and honesty</h2>
 * <ul>
 *   <li>This is a <b>screening instrument</b>, not a benchmark: it tells an operator which claims are worth
 *       keeping and which are indistinguishable from noise. A {@code NOISE} verdict means "this run could
 *       not show a difference under this load", not "this feature does nothing".</li>
 *   <li>It requires load. Run it with real players or a bot harness connected, or it will say so.</li>
 *   <li>It restores the operator's pinned override on completion, cancellation, and abort, so scoping never
 *       silently changes a running server's configuration.</li>
 * </ul>
 */
public final class AgcFeatureScoper {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcFeatureScoper.class);
    private static final AgcFeatureScoper INSTANCE = new AgcFeatureScoper();

    /** Below this median MSPT the tick loop is too idle for a feature's cost to be visible. */
    public static final double MIN_LOAD_MSPT = 1.0D;
    /**
     * Smallest effect, as a fraction of the run's load, that is worth calling a verdict. 0.5% of a 20 ms
     * tick is 0.1 ms; below that a change is not something an operator can act on, and calling it HELPS
     * would be exactly the kind of unbacked claim this instrument exists to stop.
     */
    public static final double MIN_RELATIVE_EFFECT = 0.005D;

    /** Default window length in ticks (6 seconds at 20 TPS). */
    public static final int DEFAULT_WINDOW_TICKS = 120;
    /** Default number of ABBA pairs (8 windows, 48 seconds). */
    public static final int DEFAULT_PAIRS = 2;
    /** Bounds that keep a run short enough to be an operator action rather than an outage. */
    private static final int MIN_WINDOW_TICKS = 40;
    private static final int MAX_WINDOW_TICKS = 1_200;
    private static final int MIN_PAIRS = 1;
    private static final int MAX_PAIRS = 16;
    /** Completed verdicts retained in memory. */
    private static final int MAX_RESULTS = 128;

    /** What a scoping run concluded about one feature. */
    public enum Verdict {
        /** Never scoped. */
        UNMEASURED,
        /** Toggling the feature made the tick measurably faster. */
        HELPS,
        /** Toggling the feature made the tick measurably slower. */
        HURTS,
        /** The difference was not larger than the run-to-run spread: indistinguishable from noise. */
        NOISE,
        /** The server was too idle for the feature's cost to be observable. Re-run under load. */
        INCONCLUSIVE_IDLE,
        /** The run was cancelled. */
        ABORTED
    }

    /** One arm of the pair schedule. */
    private enum Arm {
        OFF,
        ON
    }

    /**
     * The outcome of one complete scoping run.
     *
     * @param feature       the feature that was scoped
     * @param verdict       what the run concluded
     * @param offMedianMspt median tick time over the OFF windows
     * @param onMedianMspt  median tick time over the ON windows
     * @param deltaMspt     the drift-corrected effect, {@code median(pairEffect)}; negative means the
     *                      feature helped. This is the number to trust, not {@code onMedian - offMedian}
     * @param noiseMspt     spread of the paired effects: how unreproducible the effect was
     * @param thresholdMspt the threshold the effect had to beat, {@code max(noise, load * 0.5%)}
     * @param loadMspt      median tick time over every window, i.e. how much work the server was doing
     * @param windowTicks   ticks per window
     * @param pairs         number of ABBA pairs
     * @param finishedAtEpochMs when the run finished
     */
    public record Result(
        AgcCapabilityMatrix.Feature feature,
        Verdict verdict,
        double offMedianMspt,
        double onMedianMspt,
        double deltaMspt,
        double noiseMspt,
        double thresholdMspt,
        double loadMspt,
        int windowTicks,
        int pairs,
        long finishedAtEpochMs
    ) {
        /** Human-readable one-line form used by the command and the journal. */
        public String describe() {
            return feature.name() + " " + verdict
                + " delta=" + String.format("%+.2f", deltaMspt) + "ms"
                + " (off=" + String.format("%.2f", offMedianMspt)
                + " on=" + String.format("%.2f", onMedianMspt)
                + " threshold=+- " + String.format("%.2f", thresholdMspt)
                + " [noise=" + String.format("%.2f", noiseMspt) + "]"
                + " load=" + String.format("%.2f", loadMspt) + "ms"
                + " window=" + windowTicks + "t pairs=" + pairs + ")";
        }
    }

    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedDeque<Result> results = new ConcurrentLinkedDeque<>();
    /** Features waiting for their turn, touched only by the tick thread. */
    private final Deque<AgcCapabilityMatrix.Feature> queue = new ArrayDeque<>();

    private volatile boolean running;
    private volatile String statusLine = "idle";
    private volatile AgcCapabilityMatrix.Feature activeFeature;
    private volatile int queuedCount;

    // Run state; only ever mutated on the server tick thread.
    private Arm[] schedule = new Arm[0];
    private Boolean savedOverride;
    private double[] samples = new double[0];
    private double[] offMedians = new double[0];
    private double[] onMedians = new double[0];
    private int offCount;
    private int onCount;
    private int windowIndex;
    private int tickInWindow;
    private int windowTicks = DEFAULT_WINDOW_TICKS;
    private int pairs = DEFAULT_PAIRS;

    private AgcFeatureScoper() {
    }

    public static AgcFeatureScoper get() {
        return INSTANCE;
    }

    // Tick thread

    /**
     * Per-tick entry point. Called from {@code MinecraftServer.tickServer} via
     * {@link AgcHotPathRuntimeBridge#onServerTickEnd(long, long)}, i.e. on the server thread with the exact
     * measured duration of the tick that just ended.
     *
     * <p>Command requests are drained here rather than applied on the command's own thread, so all run state
     * is owned by the tick thread and the hot path takes no lock.</p>
     */
    public void onTickEnd(final long tickCount, final double mspt) {
        Runnable request;
        while ((request = this.pending.poll()) != null) {
            try {
                request.run();
            } catch (final Throwable t) {
                LOGGER.warn("[AGC] feature scoper request failed", t);
                abort("request failed: " + t);
            }
        }
        if (!this.running) {
            return;
        }

        this.samples[this.tickInWindow++] = mspt;
        if (this.tickInWindow < this.windowTicks) {
            return;
        }

        final double windowMedian = medianOf(this.samples, this.windowTicks);
        if (this.schedule[this.windowIndex] == Arm.ON) {
            this.onMedians[this.onCount++] = windowMedian;
        } else {
            this.offMedians[this.offCount++] = windowMedian;
        }

        this.windowIndex++;
        if (this.windowIndex >= this.schedule.length) {
            finish();
            return;
        }
        this.applyArm();
    }

    private void applyArm() {
        final Arm arm = this.schedule[this.windowIndex];
        AgcCapabilityMatrix.setRuntimeOverride(this.activeFeature, arm == Arm.ON);
        this.tickInWindow = 0;
        this.statusLine = this.activeFeature.name() + ": window " + (this.windowIndex + 1) + "/" + this.schedule.length
            + " (" + (arm == Arm.ON ? "ON" : "OFF") + ", " + this.windowTicks + " ticks)";
    }

    private void finish() {
        final AgcCapabilityMatrix.Feature feature = this.activeFeature;
        final Result result = computeResult(feature);
        restoreOverride(feature);
        this.running = false;
        this.activeFeature = null;
        record(result);
        this.statusLine = "finished: " + result.describe();
        LOGGER.info("[AGC] feature scope finished: {}", result.describe());
        startNextQueued();
    }

    private Result computeResult(final AgcCapabilityMatrix.Feature feature) {
        if (this.offCount == 0 || this.onCount == 0) {
            return new Result(feature, Verdict.ABORTED, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
                this.windowTicks, this.pairs, System.currentTimeMillis());
        }
        final double offMedian = medianOf(this.offMedians, this.offCount);
        final double onMedian = medianOf(this.onMedians, this.onCount);

        // ABBA keeps drift out of the effect: average each pair's two ON windows and its two OFF windows
        // first, then difference them. The two windows of an arm sit symmetrically about the pair's centre,
        // so a linear trend across the run contributes equally to both means and cancels.
        final int pairCount = this.pairs;
        final double[] pairEffects = new double[pairCount];
        for (int i = 0; i < pairCount; i++) {
            final double onMean = (this.onMedians[i * 2] + this.onMedians[i * 2 + 1]) / 2.0D;
            final double offMean = (this.offMedians[i * 2] + this.offMedians[i * 2 + 1]) / 2.0D;
            pairEffects[i] = onMean - offMean;
        }
        final double delta = medianOf(pairEffects, pairCount);
        final double noise = pairCount > 1 ? spreadOf(pairEffects, pairCount) : 0.0D;

        final double[] all = new double[this.offCount + this.onCount];
        System.arraycopy(this.offMedians, 0, all, 0, this.offCount);
        System.arraycopy(this.onMedians, 0, all, this.offCount, this.onCount);
        final double load = medianOf(all, all.length);
        final double threshold = Math.max(noise, load * MIN_RELATIVE_EFFECT);

        final Verdict verdict;
        if (load < MIN_LOAD_MSPT) {
            // An idle tick loop has no cost to remove, so any delta here is meaningless by construction.
            verdict = Verdict.INCONCLUSIVE_IDLE;
        } else if (Math.abs(delta) <= threshold) {
            verdict = Verdict.NOISE;
        } else {
            verdict = delta < 0.0D ? Verdict.HELPS : Verdict.HURTS;
        }
        return new Result(feature, verdict, offMedian, onMedian, delta, noise, threshold, load,
            this.windowTicks, this.pairs, System.currentTimeMillis());
    }

    private void restoreOverride(final AgcCapabilityMatrix.Feature feature) {
        // Restore the operator's pinned state exactly, including "no override at all" (null), so that a
        // completed run cannot leave a server configured differently than it was found.
        AgcCapabilityMatrix.setRuntimeOverride(feature, this.savedOverride);
        this.savedOverride = null;
    }

    private void abort(final String reason) {
        if (!this.running) {
            return;
        }
        final AgcCapabilityMatrix.Feature feature = this.activeFeature;
        restoreOverride(feature);
        this.running = false;
        this.activeFeature = null;
        this.queue.clear();
        this.queuedCount = 0;
        final Result result = new Result(feature, Verdict.ABORTED, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
            this.windowTicks, this.pairs, System.currentTimeMillis());
        record(result);
        this.statusLine = "aborted: " + reason;
        LOGGER.info("[AGC] feature scope aborted: {} ({})", feature, reason);
    }

    private void record(final Result result) {
        if (result == null || result.feature() == null) {
            return;
        }
        this.results.addFirst(result);
        while (this.results.size() > MAX_RESULTS) {
            this.results.pollLast();
        }
        AgcStabilityJournal.get().record(AgcStabilityJournal.EventType.PROFILE_CHANGE, "FeatureScoper", result.describe());
    }

    private void startNextQueued() {
        final AgcCapabilityMatrix.Feature next = this.queue.pollFirst();
        if (next == null) {
            return;
        }
        this.queuedCount = this.queue.size();
        beginRun(next, this.pairs, this.windowTicks);
    }

    private void beginRun(final AgcCapabilityMatrix.Feature feature, final int runPairs, final int runWindowTicks) {
        this.activeFeature = feature;
        this.savedOverride = AgcCapabilityMatrix.getRuntimeOverride(feature);
        this.pairs = runPairs;
        this.windowTicks = runWindowTicks;
        this.schedule = new Arm[runPairs * 4];
        for (int i = 0; i < runPairs; i++) {
            // ABBA: the two ON windows sit between the two OFF windows, so a slow monotonic drift across the
            // run moves the OFF arm's two windows apart symmetrically about the ON arm's and cancels out of
            // the arm medians.
            this.schedule[i * 4] = Arm.OFF;
            this.schedule[i * 4 + 1] = Arm.ON;
            this.schedule[i * 4 + 2] = Arm.ON;
            this.schedule[i * 4 + 3] = Arm.OFF;
        }
        this.offMedians = new double[runPairs * 2];
        this.onMedians = new double[runPairs * 2];
        this.offCount = 0;
        this.onCount = 0;
        this.samples = new double[runWindowTicks];
        this.windowIndex = 0;
        this.tickInWindow = 0;
        this.running = true;
        this.applyArm();
    }

    // Request surface (any thread; applied on the tick thread)

    /**
     * Queues a scoping run for one feature.
     *
     * @return {@code null} when the request was accepted, otherwise the reason it was rejected
     */
    public String requestScope(final AgcCapabilityMatrix.Feature feature, final int requestedPairs, final int requestedWindowTicks) {
        if (feature == null) {
            return "unknown feature";
        }
        if (this.running) {
            return "a scope run is already in progress (" + this.statusLine + "); cancel it first";
        }
        if (requestedPairs < MIN_PAIRS || requestedPairs > MAX_PAIRS) {
            return "pairs must be " + MIN_PAIRS + ".." + MAX_PAIRS;
        }
        if (requestedWindowTicks < MIN_WINDOW_TICKS || requestedWindowTicks > MAX_WINDOW_TICKS) {
            return "window ticks must be " + MIN_WINDOW_TICKS + ".." + MAX_WINDOW_TICKS;
        }
        this.pending.add(() -> {
            if (this.running) {
                return; // raced with another accepted request; first one wins
            }
            beginRun(feature, requestedPairs, requestedWindowTicks);
        });
        this.statusLine = "queued: " + feature.name();
        return null;
    }

    /**
     * Queues a run for every feature in {@code features}, back to back, reusing the same pairs/window
     * settings. Used to sweep the claims a server is currently making.
     *
     * @return {@code null} when accepted, otherwise the reason it was rejected
     */
    public String requestScopeAll(final List<AgcCapabilityMatrix.Feature> features, final int requestedPairs, final int requestedWindowTicks) {
        if (features == null || features.isEmpty()) {
            return "no scopeable features";
        }
        if (this.running) {
            return "a scope run is already in progress (" + this.statusLine + "); cancel it first";
        }
        if (requestedPairs < MIN_PAIRS || requestedPairs > MAX_PAIRS) {
            return "pairs must be " + MIN_PAIRS + ".." + MAX_PAIRS;
        }
        if (requestedWindowTicks < MIN_WINDOW_TICKS || requestedWindowTicks > MAX_WINDOW_TICKS) {
            return "window ticks must be " + MIN_WINDOW_TICKS + ".." + MAX_WINDOW_TICKS;
        }
        final List<AgcCapabilityMatrix.Feature> snapshot = List.copyOf(features);
        this.pending.add(() -> {
            this.queue.clear();
            this.queue.addAll(snapshot);
            this.queuedCount = this.queue.size();
            this.pairs = requestedPairs;
            this.windowTicks = requestedWindowTicks;
            if (!this.running) {
                startNextQueued();
            }
        });
        this.statusLine = "queued " + snapshot.size() + " feature(s)";
        return null;
    }

    /** Requests cancellation of the active run and of anything queued. */
    public void requestCancel() {
        this.pending.add(() -> abort("cancelled by operator"));
    }

    // Read surface (any thread)

    /** Whether a scoping run (or a queue of them) is active. */
    public boolean isRunning() {
        return this.running;
    }

    /** A short human-readable description of the current state. */
    public String status() {
        return this.statusLine;
    }

    /** The feature currently being scoped, or {@code null}. */
    public AgcCapabilityMatrix.Feature activeFeature() {
        return this.activeFeature;
    }

    /** Features still waiting for their turn. */
    public int queuedCount() {
        return this.queuedCount;
    }

    /** Completed verdicts, most recent first. */
    public List<Result> results() {
        return new ArrayList<>(this.results);
    }

    /** The most recent verdict for {@code feature}, or {@code null}. */
    public Result result(final AgcCapabilityMatrix.Feature feature) {
        for (final Result result : this.results) {
            if (result.feature() == feature) {
                return result;
            }
        }
        return null;
    }

    /** Wall-clock estimate in seconds for a run with the given shape. */
    public static int estimateSeconds(final int pairs, final int windowTicks) {
        return (pairs * 4 * windowTicks) / 20;
    }

    /** Test/diagnostic hook: drops recorded verdicts. */
    public void resetResults() {
        this.results.clear();
    }

    // Statistics

    private static double medianOf(final double[] values, final int count) {
        if (count <= 0) {
            return 0.0D;
        }
        final double[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        final int mid = count / 2;
        return (count & 1) == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0D;
    }

    private static double spreadOf(final double[] values, final int count) {
        if (count <= 0) {
            return 0.0D;
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
        }
        return max - min;
    }
}
