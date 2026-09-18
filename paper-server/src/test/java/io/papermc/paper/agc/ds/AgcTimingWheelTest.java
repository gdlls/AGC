package io.papermc.paper.agc.ds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcTimingWheelTest {

    @BeforeEach
    public void setup() {
        AgcTimingWheel.get().clear();
    }

    @Test
    public void testImmediateAndDelayedExecution() {
        AgcTimingWheel wheel = AgcTimingWheel.get();
        AtomicBoolean ran1 = new AtomicBoolean(false);
        AtomicBoolean ran2 = new AtomicBoolean(false);

        wheel.schedule(0, () -> ran1.set(true));
        wheel.schedule(2, () -> ran2.set(true));

        assertEquals(0, ran1.get() ? 1 : 0);

        // Tick 1
        int executed1 = wheel.advanceTick();
        assertTrue(ran1.get());
        assertFalse(ran2.get());
        assertEquals(1, executed1);

        // Tick 2
        int executed2 = wheel.advanceTick();
        assertTrue(ran2.get());
        assertEquals(1, executed2);

        // Tick 3
        int executed3 = wheel.advanceTick();
        assertTrue(ran2.get());
        assertEquals(0, executed3);
    }

    @Test
    public void testTaskCancellation() {
        AgcTimingWheel wheel = AgcTimingWheel.get();
        AtomicBoolean ran = new AtomicBoolean(false);

        var handle = wheel.schedule(1, () -> ran.set(true));
        assertTrue(handle.cancel());
        assertTrue(handle.isCancelled());

        wheel.advanceTick();
        wheel.advanceTick();

        assertFalse(ran.get());
        assertEquals(1, wheel.metrics().tasksCancelled());
    }

    @Test
    public void testTierCascading() {
        AgcTimingWheel wheel = AgcTimingWheel.get();
        AtomicInteger counter = new AtomicInteger(0);

        // Schedule at Tier 2 range (e.g. 70 ticks out)
        wheel.schedule(70, counter::incrementAndGet);

        // Advance 69 ticks
        for (int i = 0; i < 69; i++) {
            wheel.advanceTick();
        }
        assertEquals(0, counter.get());

        // Advance next 2 ticks (passes tick 70)
        wheel.advanceTick();
        wheel.advanceTick();

        assertEquals(1, counter.get());
    }
}
