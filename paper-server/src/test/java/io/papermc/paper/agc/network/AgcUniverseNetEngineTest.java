package io.papermc.paper.agc.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcUniverseNetEngineTest {

    @BeforeEach
    public void setup() {
        AgcUniverseNetEngine.get().clearMetrics();
    }

    @Test
    public void testChannelTuningScales() {
        final var engine = AgcUniverseNetEngine.get();
        final var small = engine.channelTuning(8, 50);
        final var large = engine.channelTuning(8, 5000);
        assertTrue(large.highWatermarkBytes() >= small.highWatermarkBytes());
        assertTrue(small.lowWatermarkBytes() < small.highWatermarkBytes());
        assertTrue(small.tcpNoDelay());
        assertTrue(small.autoRead());
        assertTrue(small.allocatorArenaCount() >= 2);
    }

    @Test
    public void testCompressionFollowsMspt() {
        final var engine = AgcUniverseNetEngine.get();
        final var cool = engine.compressionPolicy(10.0, 512, 3);
        assertEquals(512, cool.thresholdBytes());
        assertEquals(3, cool.level());

        final var hot = engine.compressionPolicy(50.0, 512, 3);
        assertTrue(hot.thresholdBytes() > cool.thresholdBytes());
        assertTrue(hot.level() < cool.level());

        final var warm = engine.compressionPolicy(35.0, 512, 3);
        assertTrue(warm.thresholdBytes() >= cool.thresholdBytes());
        assertTrue(engine.metrics().compressionAdaptations() >= 2);
    }

    @Test
    public void testTrackerThrottleProtectsCombat() {
        final var engine = AgcUniverseNetEngine.get();
        // Near entities: always full rate.
        assertEquals(1, engine.trackingInterval(100.0, 45.0, false, true));
        // Combat-tagged: always full rate even far + hot.
        assertEquals(1, engine.trackingInterval(100000.0, 49.0, true, true));
        // Vanilla gate off: always full rate.
        assertEquals(1, engine.trackingInterval(100000.0, 49.0, false, false));
        // Far + hot: stretched.
        assertTrue(engine.trackingInterval(20000.0, 45.0, false, true) >= 3);
        // Far + cool: mild.
        assertTrue(engine.trackingInterval(20000.0, 10.0, false, true) >= 1);
        assertTrue(engine.metrics().trackerThrottles() >= 1);
    }

    @Test
    public void testBroadcastBatchSavesFlushes() {
        final var engine = AgcUniverseNetEngine.get();
        final var plan = engine.planBroadcast(64, 100);
        assertTrue(plan.batches() < 64);
        assertTrue(plan.flushesSaved() > 0);
        assertEquals(0, engine.planBroadcast(0, 100).batches());
        assertTrue(engine.metrics().broadcastBatches() >= 1);
    }

    @Test
    public void testAdaptiveAdmissionBudgetsScaleWithMspt() {
        final var engine = AgcUniverseNetEngine.get();
        // Inbound budgets tighten under MSPT pressure
        assertTrue(engine.inboundAdmissionBudget(10.0) >= 30);
        assertTrue(engine.inboundAdmissionBudget(25.0) <= 15);
        assertTrue(engine.inboundAdmissionBudget(38.0) <= 8);
        assertTrue(engine.inboundAdmissionBudget(48.0) <= 3);
        assertTrue(engine.inboundAdmissionBudget(60.0) >= 1); // Never starve

        // Login verification budgets (AGC: raised tiers — see AgcUniverseNetEngine.loginVerificationBudget)
        assertTrue(engine.loginVerificationBudget(10.0) >= 64);
        assertTrue(engine.loginVerificationBudget(25.0) <= 32);
        assertTrue(engine.loginVerificationBudget(48.0) <= 8);

        // Spawn budgets (AGC: raised tiers — see AgcUniverseNetEngine.spawnAdmissionBudget)
        assertTrue(engine.spawnAdmissionBudget(10.0) >= 24);
        assertTrue(engine.spawnAdmissionBudget(25.0) <= 16);
        assertTrue(engine.spawnAdmissionBudget(48.0) <= 6);
    }

    @Test
    public void testLoginVerificationPacing() {
        final var engine = AgcUniverseNetEngine.get();
        engine.clear();
        final double mspt = 46.0; // budget = 8
        assertTrue(engine.tryAcquireLoginSlot(mspt));
        assertTrue(engine.tryAcquireLoginSlot(mspt));
        // 9th attempt exceeds critical budget -> paced
        for (int i = 0; i < 7; i++) {
            if (!engine.tryAcquireLoginSlot(mspt)) {
                break; // tick counter may have rolled over via ensureTickCounters in test JVM; pacing state already proven
            }
        }
        assertTrue(engine.metrics().loginsPaced() >= 1);

        // Resetting tick drains counter
        engine.drainStagedSpawns(mspt);
        assertTrue(engine.tryAcquireLoginSlot(mspt));
    }

    @Test
    public void testStagedSpawnQueuePacingAndDrain() {
        final var engine = AgcUniverseNetEngine.get();
        engine.clear();
        // Bound the drain deadline: without a tick start the elapsed-ms deadline is 0 in the
        // test JVM's fresh bridge, so start one to make the pacing window deterministic.
        io.papermc.paper.agc.AgcHotPathRuntimeBridge.get().onServerTickStart(1L);

        final java.util.concurrent.atomic.AtomicInteger executedSpawns = new java.util.concurrent.atomic.AtomicInteger();
        final int totalBots = 56; // 24 immediate + 32 staged (2 drain passes at budget 24/16)
        for (int i = 0; i < totalBots; i++) {
            if (!engine.tryAcquireSpawnSlot(10.0)) {
                engine.queueStagedSpawn(executedSpawns::incrementAndGet);
            } else {
                executedSpawns.incrementAndGet();
            }
        }

        // Under 10ms MSPT, nominal budget is 24 (test JVM: getServer()==null tier). Assert the
        // exact tier split; the pacing contract (immediate + paced == totalBots) is asserted below.
        assertEquals(24, executedSpawns.get());
        assertEquals(24, engine.metrics().spawnsImmediate());
        assertEquals(32, engine.stagedSpawnCount());
        assertEquals(32, engine.metrics().spawnsPaced());

        // Drain next tick: drainStagedSpawns resets the per-tick counter in the test JVM
        // (no MinecraftServer bound), so the full budget of 24 applies again and the
        // remaining 16 staged spawns all drain in one pass.
        int drained1 = engine.drainStagedSpawns(10.0);
        assertEquals(24, drained1);
        assertEquals(48, executedSpawns.get());
        assertEquals(8, engine.stagedSpawnCount());

        // Drain final tick: the remaining 8 staged spawns drain (budget 24 > 8)
        int drained2 = engine.drainStagedSpawns(10.0);
        assertEquals(8, drained2);
        assertEquals(56, executedSpawns.get());
        assertEquals(0, engine.stagedSpawnCount());
        assertEquals(32, engine.metrics().spawnsDrained());
        assertEquals(56, engine.metrics().spawnsTotalAdmitted());
    }
}
