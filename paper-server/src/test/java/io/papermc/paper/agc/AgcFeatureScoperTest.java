package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link AgcFeatureScoper}.
 *
 * <p>These drive the scoper exactly as the server does — through {@link AgcFeatureScoper#onTickEnd} — so the
 * schedule, the verdict rule and the override restoration are all exercised on the real state machine rather
 * than on a helper.</p>
 */
class AgcFeatureScoperTest {

    private static final AgcCapabilityMatrix.Feature FEATURE = AgcCapabilityMatrix.Feature.ENTITY_TRACKER_IDLE_SKIP;
    private static final AgcCapabilityMatrix.Feature OTHER = AgcCapabilityMatrix.Feature.ACTIVATION_RANGE_ITERATE_ONCE;
    /** Minimum legal window (the scoper rejects shorter runs, so these tests must not undercut it). */
    private static final int WINDOW = 40;

    private AgcFeatureScoper scoper;

    @BeforeEach
    void setUp() {
        this.scoper = AgcFeatureScoper.get();
        this.scoper.resetResults();
        this.scoper.requestCancel();
        this.drain();
        AgcCapabilityMatrix.setRuntimeOverride(FEATURE, null);
        AgcCapabilityMatrix.setRuntimeOverride(OTHER, null);
    }

    @AfterEach
    void tearDown() {
        this.scoper.requestCancel();
        this.drain();
        AgcCapabilityMatrix.setRuntimeOverride(FEATURE, null);
        AgcCapabilityMatrix.setRuntimeOverride(OTHER, null);
    }

    /** Runs the pending command queue without advancing the schedule. */
    private void drain() {
        this.scoper.onTickEnd(0L, 0.0D);
    }

    /** Mirrors the documented schedule: ABBA, i.e. OFF, ON, ON, OFF per pair. */
    private static boolean armIsOn(final int windowIndex) {
        return (windowIndex % 4) == 1 || (windowIndex % 4) == 2;
    }

    /**
     * Feeds one complete run, choosing each window's tick time from that window's 0-based index and its arm.
     * (The index arrives as a {@code double} because it is carried by a {@link DoubleBinaryOperator}; compare
     * it as a value, never with integer division.)
     *
     * <p>The fixture drives the schedule explicitly instead of reading the live enablement, so it cannot be
     * fooled by the ambient AGC mode; {@link #appliesTheAbbaScheduleToTheLiveFeature} is what verifies the
     * scoper really applies that schedule to the feature.</p>
     */
    private void feed(final int pairs, final DoubleBinaryOperator msptFor) {
        long tick = 1L;
        for (int window = 0; window < pairs * 4; window++) {
            final double mspt = msptFor.applyAsDouble(window, armIsOn(window) ? 1.0D : 0.0D);
            for (int t = 0; t < WINDOW; t++) {
                this.scoper.onTickEnd(tick++, mspt);
            }
        }
    }

    /** Starts a single-feature run and completes it with a per-arm tick time. */
    private void run(final AgcCapabilityMatrix.Feature feature, final int pairs,
                     final DoubleUnaryOperator msptWhenOn) {
        assertNull(this.scoper.requestScope(feature, pairs, WINDOW), "the run must be accepted");
        this.drain(); // the tick thread applies the request before any sample is taken
        this.feed(pairs, (window, on) -> msptWhenOn.applyAsDouble(on));
    }

    @Test
    void appliesTheAbbaScheduleToTheLiveFeature() {
        assertNull(this.scoper.requestScope(FEATURE, 1, WINDOW));
        this.drain();

        final List<Boolean> observed = new ArrayList<>();
        for (int window = 0; window < 4; window++) {
            this.scoper.onTickEnd(1L, 10.0D); // first tick of the window
            observed.add(AgcCapabilityMatrix.isEnabled(FEATURE));
            for (int t = 1; t < WINDOW; t++) {
                this.scoper.onTickEnd(1L, 10.0D);
            }
        }

        assertEquals(List.of(false, true, true, false), observed,
            "ABBA keeps the outside windows OFF and the inside ones ON so drift cancels out of the effect");
        assertNull(AgcCapabilityMatrix.getRuntimeOverride(FEATURE), "and the override is released at the end");
    }

    @Test
    void rejectsOutOfRangeRunShapes() {
        assertNotNull(this.scoper.requestScope(FEATURE, 0, WINDOW), "pairs below the minimum must be rejected");
        assertNotNull(this.scoper.requestScope(FEATURE, 99, WINDOW), "pairs above the maximum must be rejected");
        assertNotNull(this.scoper.requestScope(FEATURE, 1, 5), "a window shorter than the minimum must be rejected");
        assertNotNull(this.scoper.requestScope(null, 1, WINDOW), "a null feature must be rejected");
    }

    @Test
    void refusesASecondRunWhileOneIsActive() {
        assertNull(this.scoper.requestScope(FEATURE, 1, WINDOW));
        this.drain();
        assertTrue(this.scoper.isRunning());

        assertNotNull(this.scoper.requestScope(OTHER, 1, WINDOW),
            "the second request must be refused instead of silently replacing the first");
    }

    @Test
    void reportsHelpsWhenTheFeatureMakesTheTickFaster() {
        run(FEATURE, 1, on -> on > 0.5D ? 4.0D : 10.0D);

        final AgcFeatureScoper.Result result = this.scoper.result(FEATURE);
        assertNotNull(result);
        assertEquals(AgcFeatureScoper.Verdict.HELPS, result.verdict(),
            "ON=4ms against OFF=10ms is far outside any threshold: " + result.describe());
        assertEquals(10.0D, result.offMedianMspt(), 0.001D);
        assertEquals(4.0D, result.onMedianMspt(), 0.001D);
        assertEquals(-6.0D, result.deltaMspt(), 0.001D);
    }

    @Test
    void reportsHurtsWhenTheFeatureMakesTheTickSlower() {
        run(FEATURE, 1, on -> on > 0.5D ? 9.0D : 6.0D);

        final AgcFeatureScoper.Result result = this.scoper.result(FEATURE);
        assertNotNull(result);
        assertEquals(AgcFeatureScoper.Verdict.HURTS, result.verdict(), result.describe());
        assertTrue(result.deltaMspt() > 0.0D, "the delta must carry the sign of the regression");
    }

    @Test
    void reportsNoiseWhenTheEffectIsNotReproducible() {
        // The ON arm is 3ms faster in the first pair and 3ms slower in the second. The paired effects cancel
        // in the median, so calling either direction would be reporting run-to-run variation as a result.
        assertNull(this.scoper.requestScope(FEATURE, 2, WINDOW));
        this.drain();
        this.feed(2, (window, on) -> on > 0.5D ? (window < 4.0D ? 7.0D : 13.0D) : 10.0D);

        final AgcFeatureScoper.Result result = this.scoper.result(FEATURE);
        assertNotNull(result);
        assertEquals(AgcFeatureScoper.Verdict.NOISE, result.verdict(), result.describe());
        assertEquals(0.0D, result.deltaMspt(), 0.001D, "the paired effects must cancel in the median");
        assertEquals(6.0D, result.noiseMspt(), 0.001D, "the spread of the paired effects is the noise");
    }

    @Test
    void reportsNoiseWhenTheEffectIsBelowTheMeaningfulFloor() {
        // A 0.02ms shift on a 10ms tick is 0.2%: real or not, it is not something an operator can act on, and
        // a single pair has no spread of its own to fall back on.
        run(FEATURE, 1, on -> on > 0.5D ? 9.98D : 10.0D);

        final AgcFeatureScoper.Result result = this.scoper.result(FEATURE);
        assertNotNull(result);
        assertEquals(AgcFeatureScoper.Verdict.NOISE, result.verdict(), result.describe());
        assertTrue(result.thresholdMspt() > 0.0D,
            "the meaningfulness floor must be what rejected this, not a zero threshold");
    }

    @Test
    void reportsInconclusiveWhenTheServerWasIdle() {
        run(FEATURE, 1, on -> on > 0.5D ? 0.10D : 0.80D);

        final AgcFeatureScoper.Result result = this.scoper.result(FEATURE);
        assertNotNull(result);
        assertEquals(AgcFeatureScoper.Verdict.INCONCLUSIVE_IDLE, result.verdict(),
            "a sub-millisecond tick loop cannot show a feature's cost: " + result.describe());
        assertTrue(result.loadMspt() < AgcFeatureScoper.MIN_LOAD_MSPT);
    }

    @Test
    void abbaScheduleCancelsMonotonicDrift() {
        // A real 4ms saving riding on a baseline that climbs 1ms per window across the run. With a plain
        // ON-then-OFF schedule the drift would swamp the effect (the arm measured last would look worse);
        // because ABBA pairs the arms symmetrically around each pair's centre, the effect must survive.
        assertNull(this.scoper.requestScope(FEATURE, 2, WINDOW));
        this.drain();
        this.feed(2, (window, on) -> window * 1.0D + (on > 0.5D ? 10.0D : 14.0D));

        final AgcFeatureScoper.Result result = this.scoper.result(FEATURE);
        assertNotNull(result);
        assertEquals(AgcFeatureScoper.Verdict.HELPS, result.verdict(),
            "the ON/OFF delta must survive the drift: " + result.describe());
        assertEquals(-4.0D, result.deltaMspt(), 0.001D, "and it must be the true effect, not drift-adjusted");
    }

    @Test
    void restoresTheOperatorsPinnedOverrideOnCompletion() {
        AgcCapabilityMatrix.setRuntimeOverride(FEATURE, Boolean.TRUE);

        run(FEATURE, 1, on -> on > 0.5D ? 4.0D : 10.0D);

        assertEquals(Boolean.TRUE, AgcCapabilityMatrix.getRuntimeOverride(FEATURE),
            "a completed run must not clear an operator's pin");
        assertTrue(AgcCapabilityMatrix.isEnabled(FEATURE));
    }

    @Test
    void restoresTheAbsenceOfAnOverrideOnCompletion() {
        assertNull(AgcCapabilityMatrix.getRuntimeOverride(FEATURE));

        run(FEATURE, 1, on -> on > 0.5D ? 4.0D : 10.0D);

        assertNull(AgcCapabilityMatrix.getRuntimeOverride(FEATURE),
            "a run must not leave a synthetic override behind that silently pins the feature on");
    }

    @Test
    void cancellationRestoresTheOverrideAndStopsSampling() {
        assertNull(this.scoper.requestScope(FEATURE, 4, WINDOW));
        this.drain();
        assertTrue(this.scoper.isRunning());

        for (int i = 0; i < WINDOW / 2; i++) { // half a window, then cancel mid-run
            this.scoper.onTickEnd(i + 1L, 10.0D);
        }
        this.scoper.requestCancel();
        this.drain();

        assertFalse(this.scoper.isRunning());
        assertNull(AgcCapabilityMatrix.getRuntimeOverride(FEATURE), "the override must be released on cancel");
        final AgcFeatureScoper.Result result = this.scoper.result(FEATURE);
        assertNotNull(result, "an aborted run is still recorded so it cannot look like a never-run feature");
        assertEquals(AgcFeatureScoper.Verdict.ABORTED, result.verdict());
    }

    @Test
    void queuedFeaturesRunBackToBackWithTheirOwnVerdicts() {
        assertNull(this.scoper.requestScopeAll(List.of(FEATURE, OTHER), 1, WINDOW));
        this.drain();

        // The first feature helps (ON=4ms, OFF=10ms); the second hurts (ON=11ms, OFF=10ms). Each delta must be
        // attributed to the feature actually being toggled at that moment, read from the scoper rather than
        // assumed, so the fixture stays independent of the ambient AGC mode.
        long tick = 1L;
        for (int i = 0; i < 2 * (4 * WINDOW + 2); i++) {
            final AgcCapabilityMatrix.Feature active = this.scoper.activeFeature();
            final boolean on = active != null && AgcCapabilityMatrix.isEnabled(active);
            final double mspt = active == FEATURE ? (on ? 4.0D : 10.0D) : (on ? 11.0D : 10.0D);
            this.scoper.onTickEnd(tick++, mspt);
        }

        final AgcFeatureScoper.Result first = this.scoper.result(FEATURE);
        final AgcFeatureScoper.Result second = this.scoper.result(OTHER);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(AgcFeatureScoper.Verdict.HELPS, first.verdict(), first.describe());
        assertEquals(AgcFeatureScoper.Verdict.HURTS, second.verdict(), second.describe());
        assertEquals(0, this.scoper.queuedCount(), "the queue must drain completely");
        assertFalse(this.scoper.isRunning());
    }

    @Test
    void resultsAreRetainedMostRecentFirst() {
        run(FEATURE, 1, on -> on > 0.5D ? 4.0D : 10.0D);
        run(OTHER, 1, on -> on > 0.5D ? 9.0D : 6.0D);

        final List<AgcFeatureScoper.Result> results = this.scoper.results();
        assertEquals(2, results.size());
        assertEquals(OTHER, results.get(0).feature(), "the newest verdict must come first");
        assertEquals(FEATURE, results.get(1).feature());
        assertEquals(results.get(0), this.scoper.result(OTHER));
    }

    @Test
    void estimateMatchesTheScheduleLength() {
        assertEquals((2 * 4 * 120) / 20, AgcFeatureScoper.estimateSeconds(2, 120));
        assertEquals(24, AgcFeatureScoper.estimateSeconds(1, 120));
    }
}
