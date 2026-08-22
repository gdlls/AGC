package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgcCapabilityMatrix} mode gating.
 */
class AgcCapabilityMatrixTest {

    @BeforeEach
    void resetState() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        AgcCapabilityMatrix.clearRuntimeOverrides();
    }

    @AfterEach
    void cleanup() {
        AgcCapabilityMatrix.clearRuntimeOverrides();
    }

    @Test
    void vanillaModeDisablesEverything() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.VANILLA);
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            assertFalse(AgcCapabilityMatrix.isEnabled(f),
                "VANILLA mode should disable " + f);
        }
    }

    @Test
    void baselineModeEnablesVanillaSafeAndBaseline() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        // NET_* watermarks는 VANILLA_SAFE → 활성
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_CHANNEL_WATERMARK));
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_READ_TIMEOUT));
        // CHUNK_SEND_BUDGET는 BASELINE → 활성
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.CHUNK_SEND_BUDGET));
        // CHUNK_PACKET_CACHE는 AGGRESSIVE_BUT_SAFE → 비활성
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.CHUNK_PACKET_CACHE));
        // NET_ZSTD는 AGGRESSIVE_BUT_SAFE → 비활성
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION));
        // PACKET_PRIORITY는 AGGRESSIVE_BUT_SAFE → 비활성
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_PACKET_PRIORITY));
    }

    @Test
    void aggressiveModeEnablesAggressiveFeatures() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            // 전부 활성 (현재 정의된 feature는 모두 AGGRESSIVE_BUT_SAFE까지)
            assertTrue(AgcCapabilityMatrix.isEnabled(f),
                "AGGRESSIVE mode should enable " + f);
        }
    }

    @Test
    void runtimeOverrideBeatsMode() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        // aggressive에선 CHUNK_PACKET_CACHE 활성
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.CHUNK_PACKET_CACHE));

        // override로 끄기
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.CHUNK_PACKET_CACHE, false);
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.CHUNK_PACKET_CACHE));

        // override로 다시 켜기
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.CHUNK_PACKET_CACHE, true);
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.CHUNK_PACKET_CACHE));
    }

    @Test
    void clearOverrideRestoresModeDefault() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
        AgcCapabilityMatrix.setRuntimeOverride(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION, true);
        assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION));

        AgcCapabilityMatrix.clearRuntimeOverrides();
        assertFalse(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.NETWORK_ZSTD_COMPRESSION));
    }

    @Test
    void reportContainsModeName() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        final String report = AgcCapabilityMatrix.report();
        assertNotNull(report);
        assertTrue(report.contains("AGC_AGGRESSIVE"), "Report should mention mode");
    }

    @Test
    void nullModeRejected() {
        try {
            AgcCapabilityMatrix.setMode(null);
            org.junit.jupiter.api.Assertions.fail("Expected NullPointerException");
        } catch (final NullPointerException expected) {
            // ok
        }
    }

    @Test
    void nullFeatureReturnsFalse() {
        assertFalse(AgcCapabilityMatrix.isEnabled(null));
    }

    @Test
    void activeBySafetyGroupsCorrectly() {
        AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_AGGRESSIVE);
        final var grouped = AgcCapabilityMatrix.activeBySafety();
        assertNotNull(grouped);
        assertTrue(grouped.containsKey(AgcPerformanceTuning.Safety.VANILLA_SAFE));
        assertTrue(grouped.containsKey(AgcPerformanceTuning.Safety.BASELINE));
        assertTrue(grouped.containsKey(AgcPerformanceTuning.Safety.AGGRESSIVE_BUT_SAFE));
        // aggressive에서도 모든 feature는 (현재 정의상) EXPERIMENTAL이 없으므로 모두 active
        for (final var list : grouped.values()) {
            assertNotNull(list);
        }
    }

    @Test
    void featureHasDescriptionAndSafety() {
        for (final AgcCapabilityMatrix.Feature f : AgcCapabilityMatrix.Feature.values()) {
            assertNotNull(f.description());
            assertFalse(f.description().isEmpty());
            assertNotNull(f.safety());
        }
    }

    @Test
    void modeEnumHasThreeValues() {
        assertEquals(3, AgcCapabilityMatrix.Mode.values().length);
    }
}
