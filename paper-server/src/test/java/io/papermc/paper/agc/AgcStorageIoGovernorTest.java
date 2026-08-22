package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcStorageIoGovernor}.
 */
class AgcStorageIoGovernorTest {

    @BeforeEach
    @AfterEach
    void resetGovernor() {
        AgcStorageIoGovernor.get().reset();
    }

    @Test
    void tokenBucketPermitsNormalAcquisition() {
        final var gov = AgcStorageIoGovernor.get();
        gov.configure(100.0, 10.0);

        // Within capacity
        assertTrue(gov.tryAcquire(AgcStorageIoGovernor.SavePriority.BACKGROUND_AUTOSAVE, 50.0));
        assertEquals(50.0, gov.availableTokens(), 1.0);

        // Exceeding capacity -> Throttled
        assertFalse(gov.tryAcquire(AgcStorageIoGovernor.SavePriority.BACKGROUND_AUTOSAVE, 60.0));

        final var m = gov.metrics();
        assertEquals(1, m.savesAdmitted());
        assertEquals(1, m.savesThrottled());
    }

    @Test
    void criticalPriorityBypassesTokenDeficit() {
        final var gov = AgcStorageIoGovernor.get();
        gov.configure(10.0, 1.0);

        // Drain tokens
        assertTrue(gov.tryAcquire(AgcStorageIoGovernor.SavePriority.BACKGROUND_AUTOSAVE, 10.0));

        // Normal save throttled
        assertFalse(gov.tryAcquire(AgcStorageIoGovernor.SavePriority.BACKGROUND_AUTOSAVE, 5.0));

        // Critical player write always admitted
        assertTrue(gov.tryAcquire(AgcStorageIoGovernor.SavePriority.CRITICAL_HOT, 5.0));
        assertTrue(gov.availableTokens() < 0.0); // Debt allowed for critical writes
    }

    @Test
    void prioritizedDrainOrdersByBand() {
        final var gov = AgcStorageIoGovernor.get();
        gov.configure(1000.0, 100.0);

        gov.enqueueSave("world_cold", AgcStorageIoGovernor.SavePriority.ARCHIVE_COLD, "cold_chunk", 10);
        gov.enqueueSave("world_hot", AgcStorageIoGovernor.SavePriority.CRITICAL_HOT, "hot_chunk", 10);
        gov.enqueueSave("world_warm", AgcStorageIoGovernor.SavePriority.HIGH_DRAIN, "warm_chunk", 10);

        final List<String> executionOrder = new ArrayList<>();
        final int drained = gov.drain(10, task -> executionOrder.add(task.worldKey()));

        assertEquals(3, drained);
        assertEquals(List.of("world_hot", "world_warm", "world_cold"), executionOrder);
    }
}
