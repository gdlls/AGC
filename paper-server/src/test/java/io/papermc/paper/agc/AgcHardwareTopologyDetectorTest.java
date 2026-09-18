package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgcHardwareTopologyDetector} universal platform detection.
 */
class AgcHardwareTopologyDetectorTest {

    @Test
    void detectorInitializesProfile() {
        final AgcHardwareTopologyDetector detector = AgcHardwareTopologyDetector.get();
        assertNotNull(detector, "Detector instance should not be null");

        final AgcHardwareTopologyDetector.HardwareProfile profile = detector.profile();
        assertNotNull(profile, "Profile should not be null");

        assertNotNull(profile.arch(), "ArchType must be detected");
        assertNotNull(profile.os(), "OsType must be detected");
        assertTrue(profile.logicalCores() >= 1, "Logical cores must be at least 1");
        assertTrue(profile.physicalCoresEstimate() >= 1, "Physical cores estimate must be at least 1");
        assertTrue(profile.numaNodesEstimate() >= 1, "NUMA nodes estimate must be at least 1");
        assertNotNull(profile.simd(), "SIMD capability must be detected");
        assertNotNull(profile.networkBackend(), "Network backend must be selected");
        assertNotNull(profile.memoryTier(), "Memory tier must be classified");
        assertTrue(profile.maxMemoryBytes() > 0, "Max memory must be positive");

        assertTrue(profile.recommendedWorldTickWorkers() >= 2, "World tick workers >= 2");
        assertTrue(profile.recommendedWorkStealingWorkers() >= 2, "Work stealing workers >= 2");
        assertTrue(profile.recommendedNettyWorkers() >= 2, "Netty workers >= 2");
        assertTrue(profile.recommendedIoWorkers() >= 2, "I/O workers >= 2");
    }

    @Test
    void allEnumsHaveDescriptions() {
        for (final AgcHardwareTopologyDetector.SimdCapability simd : AgcHardwareTopologyDetector.SimdCapability.values()) {
            assertNotNull(simd.description());
        }
        for (final AgcHardwareTopologyDetector.NetworkBackend backend : AgcHardwareTopologyDetector.NetworkBackend.values()) {
            assertNotNull(backend.description());
        }
        for (final AgcHardwareTopologyDetector.MemoryTier tier : AgcHardwareTopologyDetector.MemoryTier.values()) {
            assertNotNull(tier.description());
        }
    }
}
