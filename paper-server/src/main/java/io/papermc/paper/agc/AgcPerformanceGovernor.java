package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AGC — Dynamic Adaptive Performance Governor & State Engine.
 *
 * <p>Continuously observes the server's telemetry (rolling MSPT, memory pressure, active player
 * density) and transitions the server runtime across 4 adaptive performance profiles.</p>
 */
public final class AgcPerformanceGovernor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcPerformanceGovernor.class);
    private static final AgcPerformanceGovernor INSTANCE = new AgcPerformanceGovernor();

    public enum State {
        /** MSPT &lt; 30ms, Heap &lt; 75%: Full aggressive performance defaults */
        HEALTHY,
        /** MSPT 30-40ms, Heap 75-85%: Moderate background batching */
        ELEVATED_LOAD,
        /** MSPT 40-48ms, Heap 85-90%: Aggressive entity throttling &amp; tighter view distance */
        HIGH_CONGESTION,
        /** MSPT &gt; 48ms or Heap &gt; 90%: Emergency semantic defense to protect 20 TPS */
        EMERGENCY_GUARD
    }

    private final AtomicReference<State> currentState = new AtomicReference<>(State.HEALTHY);
    private final AtomicLong stateTransitions = new AtomicLong();

    public static AgcPerformanceGovernor get() {
        return INSTANCE;
    }

    private AgcPerformanceGovernor() {}

    /**
     * Evaluates server telemetry and updates governor state accordingly.
     *
     * @param rollingMspt Average tick time in milliseconds
     * @param usedMemoryRatio Current JVM heap used memory ratio (0.0 to 1.0)
     * @param activePlayers Total active player count
     * @return Current active {@link State}
     */
    public State evaluate(final double rollingMspt, final double usedMemoryRatio, final int activePlayers) {
        State target;

        if (rollingMspt >= 48.0 || usedMemoryRatio >= 0.92) {
            target = State.EMERGENCY_GUARD;
        } else if (rollingMspt >= 40.0 || usedMemoryRatio >= 0.85 || activePlayers >= 500) {
            target = State.HIGH_CONGESTION;
        } else if (rollingMspt >= 30.0 || usedMemoryRatio >= 0.75 || activePlayers >= 250) {
            target = State.ELEVATED_LOAD;
        } else {
            target = State.HEALTHY;
        }

        final State prev = this.currentState.getAndSet(target);
        if (prev != target) {
            this.stateTransitions.incrementAndGet();
            final String msg = String.format("Governor state changed: %s -> %s (MSPT: %.1fms, Mem: %.1f%%, Players: %d)",
                prev.name(), target.name(), rollingMspt, usedMemoryRatio * 100.0, activePlayers);
            LOGGER.info("[AGC] {}", msg);
            AgcStabilityJournal.get().record(
                AgcStabilityJournal.EventType.PROFILE_CHANGE,
                "Governor",
                msg
            );
        }

        return target;
    }

    public State getState() {
        return this.currentState.get();
    }

    public void setStateDirect(final State newState) {
        if (newState != null) {
            final State prev = this.currentState.getAndSet(newState);
            if (prev != newState) {
                this.stateTransitions.incrementAndGet();
                AgcStabilityJournal.get().record(
                    AgcStabilityJournal.EventType.SAFETY_OVERRIDE,
                    "Governor",
                    "Manual state set: " + prev.name() + " -> " + newState.name()
                );
            }
        }
    }

    public void resetMetrics() {
        this.currentState.set(State.HEALTHY);
        this.stateTransitions.set(0);
    }

    public GovernorMetrics metrics() {
        return new GovernorMetrics(
            this.currentState.get(),
            this.stateTransitions.get()
        );
    }

    public record GovernorMetrics(
        State currentState,
        long totalTransitions
    ) {
    }
}
