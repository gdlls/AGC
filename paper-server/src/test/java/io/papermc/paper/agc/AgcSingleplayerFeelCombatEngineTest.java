package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class AgcSingleplayerFeelCombatEngineTest {

    private AgcSingleplayerFeelCombatEngine engine;
    private final UUID testVictimId = UUID.randomUUID();

    @BeforeEach
    public void setUp() {
        engine = AgcSingleplayerFeelCombatEngine.getInstance();
        engine.clear();
        engine.clearOverride();
    }

    @AfterEach
    public void tearDown() {
        engine.clear();
        engine.clearOverride();
    }

    @Test
    public void testVanillaParityWhenDisabledByDefault() {
        assertFalse(engine.isEnabled(), "Combat engine must be disabled by default for 100% vanilla parity");

        final AgcSingleplayerFeelCombatEngine.PlayerCombatState victimState = engine.getOrCreateState(testVictimId);
        victimState.setPing(80.0);

        final AgcSingleplayerFeelCombatEngine.CombatVector3 original =
            new AgcSingleplayerFeelCombatEngine.CombatVector3(0.3, 0.1, 0.3);

        final AgcSingleplayerFeelCombatEngine.CombatVector3 result =
            engine.calculateKnockbackTrajectory(victimState, original, 0.5, true, 1.0, 0, 0.0);

        assertEquals(original.x(), result.x(), 1e-6);
        assertEquals(original.y(), result.y(), 1e-6);
        assertEquals(original.z(), result.z(), 1e-6);

        final AtomicBoolean dispatched = new AtomicBoolean(false);
        final boolean success = engine.dispatchSubTickKnockback(victimState, original, () -> dispatched.set(true));
        assertFalse(success);
        assertFalse(dispatched.get());
    }

    @Test
    public void testGroundPredictionAndUpwardRestoration() {
        engine.setEnabled(true);
        assertTrue(engine.isEnabled());

        final AgcSingleplayerFeelCombatEngine.PlayerCombatState victimState = engine.getOrCreateState(testVictimId);
        victimState.setPing(120.0);
        victimState.setPing(120.0);

        final AgcSingleplayerFeelCombatEngine.CombatVector3 original =
            new AgcSingleplayerFeelCombatEngine.CombatVector3(0.2, 0.0, 0.2);

        final AgcSingleplayerFeelCombatEngine.CombatVector3 compensated =
            engine.calculateKnockbackTrajectory(victimState, original, 0.05, true, 1.0, 0, 0.0);

        assertEquals(0.40, compensated.y(), 1e-4, "Should compensate vertical velocity to 0.40 for sprint hit");
        assertEquals(1, engine.getGroundCompensationsApplied());
        assertEquals(1, engine.getTotalCombatHitsProcessed());
    }

    @Test
    public void testOffGroundDecayCompensation() {
        engine.setEnabled(true);
        engine.setOffGroundSyncEnabled(true);

        final AgcSingleplayerFeelCombatEngine.PlayerCombatState victimState = engine.getOrCreateState(testVictimId);
        victimState.setPing(100.0);
        victimState.setPing(100.0);

        final AgcSingleplayerFeelCombatEngine.CombatVector3 original =
            new AgcSingleplayerFeelCombatEngine.CombatVector3(0.3, 0.4, 0.3);

        final AgcSingleplayerFeelCombatEngine.CombatVector3 compensated =
            engine.calculateKnockbackTrajectory(victimState, original, 3.0, false, 0.5, 0, 0.0);

        assertEquals(1, engine.getOffGroundCompensationsApplied());
        assertTrue(compensated.y() != original.y());
    }

    @Test
    public void testDamageTicksCeiling() {
        engine.setEnabled(true);

        final AgcSingleplayerFeelCombatEngine.PlayerCombatState victimState = engine.getOrCreateState(testVictimId);
        victimState.setPing(100.0);
        victimState.setPing(100.0);
        victimState.setLastDamageTicks(10);

        final AgcSingleplayerFeelCombatEngine.CombatVector3 original =
            new AgcSingleplayerFeelCombatEngine.CombatVector3(0.2, 0.0, 0.2);

        final AgcSingleplayerFeelCombatEngine.CombatVector3 result =
            engine.calculateKnockbackTrajectory(victimState, original, 0.05, true, 1.0, 0, 0.0);

        assertEquals(original.y(), result.y(), 1e-4);
    }

    @Test
    public void testSubTickImmediateDispatchAndMicroFlush() {
        engine.setEnabled(true);

        final AgcSingleplayerFeelCombatEngine.PlayerCombatState state = engine.getOrCreateState(testVictimId);
        final AgcSingleplayerFeelCombatEngine.CombatVector3 motion =
            new AgcSingleplayerFeelCombatEngine.CombatVector3(0.4, 0.4, 0.4);

        final AtomicBoolean packetSent = new AtomicBoolean(false);

        final boolean dispatched = engine.dispatchSubTickKnockback(state, motion, () -> packetSent.set(true));

        assertTrue(dispatched);
        assertTrue(packetSent.get(), "Immediate Netty micro-flush callback must be invoked");
        assertEquals(1, engine.getSubTickPacketsDispatched());
        assertEquals(1, engine.getMicroFlushesTriggered());
        assertTrue(engine.getAverageDispatchLatencyMicros() >= 0.0);
    }

    @Test
    public void testAntiGhostingDeduplication() {
        engine.setEnabled(true);

        final AgcSingleplayerFeelCombatEngine.PlayerCombatState state = engine.getOrCreateState(testVictimId);
        final AgcSingleplayerFeelCombatEngine.CombatVector3 motion =
            new AgcSingleplayerFeelCombatEngine.CombatVector3(0.4, 0.4, 0.4);

        final AtomicBoolean packet1 = new AtomicBoolean(false);
        final AtomicBoolean packet2 = new AtomicBoolean(false);

        final boolean first = engine.dispatchSubTickKnockback(state, motion, () -> packet1.set(true));
        assertTrue(first);
        assertTrue(packet1.get());

        final boolean second = engine.dispatchSubTickKnockback(state, motion, () -> packet2.set(true));
        assertFalse(second, "Rapid duplicate velocity must be suppressed to avoid client stutter");
        assertFalse(packet2.get());
    }

    @Test
    public void testSpikePingCompensation() {
        final AgcSingleplayerFeelCombatEngine.PlayerCombatState state = engine.getOrCreateState(testVictimId);
        state.setPing(20.0);
        assertEquals(1.0, state.getCompensatedPingMs());

        state.setPing(150.0);
        assertEquals(1.0, state.getCompensatedPingMs());
    }

    @Test
    public void testTelemetryAndReportGeneration() {
        engine.setEnabled(true);
        final String report = engine.generateReport();
        assertNotNull(report);
        assertTrue(report.contains("AGC Native KBSync & Singleplayer-Feel Combat Report"));
        assertTrue(report.contains("Engine Active             : true"));
    }
}


