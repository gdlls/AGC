package io.papermc.paper.agc.profiling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcMemoryTracker}.
 */
public class AgcMemoryTrackerTest {

    @Test
    void verifiesMemoryTrackerMetrics() {
        final AgcMemoryTracker tracker = AgcMemoryTracker.get();

        // Check heap and direct memory queries
        assertTrue(tracker.getUsedHeapBytes() > 0);
        assertTrue(tracker.getMaxHeapBytes() > 0);
        assertTrue(tracker.getHeapUsageRatio() >= 0.0 && tracker.getHeapUsageRatio() <= 1.0);

        // Simulate tick telemetry updates
        tracker.onTick(1, 20.0);
        tracker.onTick(2, 25.0);

        // Check report rendering
        final String report = tracker.getMemoryReport();
        assertNotNull(report);
        assertTrue(report.contains("Heap Usage"));
        assertTrue(report.contains("Direct Memory"));

        final String json = tracker.getGcStatsJson();
        assertNotNull(json);
        assertTrue(json.contains("\"heapUsedBytes\""));
    }
}
