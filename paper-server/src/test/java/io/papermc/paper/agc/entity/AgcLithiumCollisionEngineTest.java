package io.papermc.paper.agc.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcLithiumCollisionEngineTest {

    @BeforeEach
    public void setup() {
        AgcLithiumCollisionEngine.get().clear();
    }

    @Test
    public void testFluidPushSkip() {
        final var engine = AgcLithiumCollisionEngine.get();
        assertTrue(engine.shouldSkipFluidPush(false, true));
        assertTrue(engine.shouldSkipFluidPush(true, false));
        assertFalse(engine.shouldSkipFluidPush(true, true));
        assertTrue(engine.metrics().fluidPushSkips() == 2L);
    }

    @Test
    public void testSuffocationFastPath() {
        final var engine = AgcLithiumCollisionEngine.get();
        assertTrue(engine.isDefinitelyNotSuffocating(false, false));
        assertTrue(engine.isDefinitelyNotSuffocating(true, true));
        assertFalse(engine.isDefinitelyNotSuffocating(true, false));
        assertTrue(engine.metrics().suffocationFastPaths() == 2L);
    }

    @Test
    public void testPushPairSkip() {
        final var engine = AgcLithiumCollisionEngine.get();
        // Normal pushable pair: no skip.
        assertFalse(engine.shouldSkipPushPair(false, false, false, false, false));
        // noPhysics / same vehicle / neither pushable all skip.
        assertTrue(engine.shouldSkipPushPair(true, false, false, false, false));
        assertTrue(engine.shouldSkipPushPair(false, true, false, false, false));
        assertTrue(engine.shouldSkipPushPair(false, false, true, false, false));
        // Paper onlyPlayersCollide without a player participant skips.
        assertTrue(engine.shouldSkipPushPair(false, false, false, true, false));
        assertFalse(engine.shouldSkipPushPair(false, false, false, true, true));
    }

    @Test
    public void testProjectilePairSkip() {
        final var engine = AgcLithiumCollisionEngine.get();
        assertTrue(engine.shouldSkipProjectilePair("minecraft:ender_pearl", "minecraft:ender_pearl"));
        assertTrue(engine.shouldSkipProjectilePair("minecraft:snowball", "minecraft:snowball"));
        assertFalse(engine.shouldSkipProjectilePair("minecraft:ender_pearl", "minecraft:snowball"));
        assertFalse(engine.shouldSkipProjectilePair("minecraft:arrow", "minecraft:arrow"));
        assertFalse(engine.shouldSkipProjectilePair(null, "minecraft:egg"));
        assertTrue(engine.metrics().projectilePairSkips() == 2L);
    }

    @Test
    public void testCrammingEarlyTermination() {
        final var engine = AgcLithiumCollisionEngine.get();
        // 10 non-passengers, max 6 -> damage (threshold 5 exceeded).
        assertTrue(engine.crammingDamageApplies(new boolean[10], 10, 6));
        // 4 non-passengers, max 6 -> safe.
        assertFalse(engine.crammingDamageApplies(new boolean[10], 4, 6));
        // All passengers -> safe regardless of crowd size.
        final boolean[] allPassengers = new boolean[30];
        java.util.Arrays.fill(allPassengers, true);
        assertFalse(engine.crammingDamageApplies(allPassengers, 30, 6));
        // Disabled gamerule -> never.
        assertFalse(engine.crammingDamageApplies(new boolean[100], 100, 0));
        assertTrue(engine.metrics().crammingEarlyTerminations() == 1L);
    }

    @Test
    public void testNmsCrammingHookShortCircuits() {
        final var engine = AgcLithiumCollisionEngine.get();
        // Null / empty / below-threshold consume no RNG and never damage.
        final java.util.concurrent.atomic.AtomicInteger rolls = new java.util.concurrent.atomic.AtomicInteger();
        assertFalse(engine.shouldApplyCrammingDamage(null, 6, rolls::incrementAndGet));
        assertFalse(engine.shouldApplyCrammingDamage(java.util.List.of(), 6, rolls::incrementAndGet));
        assertEquals(0, rolls.get());
        assertFalse(engine.shouldApplyCrammingDamage(java.util.List.of(), 0, rolls::incrementAndGet));
    }

    @Test
    public void testSameClassProjectileSkip() {
        final var engine = AgcLithiumCollisionEngine.get();
        assertFalse(engine.shouldSkipSameClassProjectilePair(null));
        assertFalse(engine.shouldSkipSameClassProjectilePair(String.class));
        assertFalse(engine.shouldSkipSameClassProjectilePair(net.minecraft.world.entity.projectile.Projectile.class));
    }
}
