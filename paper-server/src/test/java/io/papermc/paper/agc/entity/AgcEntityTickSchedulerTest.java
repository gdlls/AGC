package io.papermc.paper.agc.entity;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcEntityTickSchedulerTest {

    @BeforeEach
    void setUp() {
        AgcEntityTickScheduler.get().resetMetrics();
    }

    @Test
    void verifiesEntityCostCategorization() {
        assertEquals(AgcEntityTickScheduler.EntityCostCategory.HEAVY, AgcEntityTickScheduler.categorize(EntityType.VILLAGER));
        assertEquals(AgcEntityTickScheduler.EntityCostCategory.HEAVY, AgcEntityTickScheduler.categorize(EntityType.WARDEN));
        assertEquals(AgcEntityTickScheduler.EntityCostCategory.MEDIUM, AgcEntityTickScheduler.categorize(EntityType.ZOMBIE));
        assertEquals(AgcEntityTickScheduler.EntityCostCategory.MEDIUM, AgcEntityTickScheduler.categorize(EntityType.COW));
        assertEquals(AgcEntityTickScheduler.EntityCostCategory.LIGHT, AgcEntityTickScheduler.categorize(EntityType.ITEM));
        assertEquals(AgcEntityTickScheduler.EntityCostCategory.LIGHT, AgcEntityTickScheduler.categorize(EntityType.ARROW));
        assertEquals(AgcEntityTickScheduler.EntityCostCategory.TRIVIAL, AgcEntityTickScheduler.categorize(EntityType.ARMOR_STAND));
        assertEquals(AgcEntityTickScheduler.EntityCostCategory.TRIVIAL, AgcEntityTickScheduler.categorize(EntityType.MARKER));
    }

    @Test
    void verifiesTickSchedulingAndMetrics() {
        final var scheduler = AgcEntityTickScheduler.get();

        // 1. When near player -> always ticks
        assertTrue(scheduler.shouldTickEntity(10, AgcEntityTickScheduler.EntityCostCategory.HEAVY, 1, true));
        assertTrue(scheduler.shouldTickEntity(11, AgcEntityTickScheduler.EntityCostCategory.TRIVIAL, 1, true));

        // 2. Far from player -> Trivial ticks 1 in 4 ticks
        assertFalse(scheduler.shouldTickEntity(0, AgcEntityTickScheduler.EntityCostCategory.TRIVIAL, 1, false));
        assertTrue(scheduler.shouldTickEntity(0, AgcEntityTickScheduler.EntityCostCategory.TRIVIAL, 0, false));

        final var m = scheduler.metrics();
        assertTrue(m.heavyDispatched() >= 1);
        assertTrue(m.trivialSkipped() >= 1);
    }

    @Test
    void jitDispatcherPathExecutesWithoutChangingDecisions() {
        final var scheduler = AgcEntityTickScheduler.get();
        final var dispatcher = io.papermc.paper.agc.jit.AgcTypeDispatcher.get();
        dispatcher.clear();
        try {
            // JIT gate is enabled by default: the per-entity decision must flow through it.
            assertTrue(io.papermc.paper.agc.AgcCapabilityMatrix.isEnabled(
                io.papermc.paper.agc.AgcCapabilityMatrix.Feature.JIT_TYPE_DISPATCHER));
            assertTrue(scheduler.shouldTickEntity(7, AgcEntityTickScheduler.EntityCostCategory.HEAVY, 3, true));
            assertTrue(dispatcher.metrics().primitiveDispatches() >= 1,
                "entity tick decision must exercise the JIT dispatcher when the gate is on");

            // Gate off: identical decisions, dispatcher untouched.
            dispatcher.clear();
            io.papermc.paper.agc.AgcCapabilityMatrix.setRuntimeOverride(
                io.papermc.paper.agc.AgcCapabilityMatrix.Feature.JIT_TYPE_DISPATCHER, Boolean.FALSE);
            assertTrue(scheduler.shouldTickEntity(7, AgcEntityTickScheduler.EntityCostCategory.HEAVY, 3, true));
            assertEquals(0, dispatcher.metrics().primitiveDispatches());
        } finally {
            io.papermc.paper.agc.AgcCapabilityMatrix.clearRuntimeOverrides();
            dispatcher.clear();
        }
    }
}
