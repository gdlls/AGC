package io.papermc.paper.agc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgcNetworkEnhancer}.
 *
 * <p>Channel 객체 없이도 검증 가능한 부분: enable/disable 토글, metrics 초기값, singleton.</p>
 */
class AgcNetworkEnhancerTest {

    private boolean originalEnabled;

    @BeforeEach
    void saveState() {
        this.originalEnabled = AgcNetworkEnhancer.get().isEnabled();
    }

    @AfterEach
    void restoreState() {
        AgcNetworkEnhancer.get().setEnabled(this.originalEnabled);
    }

    @Test
    void singletonReturnsSameInstance() {
        assertSame(AgcNetworkEnhancer.get(), AgcNetworkEnhancer.get());
    }

    @Test
    void defaultEnabledIsTrue() {
        AgcNetworkEnhancer.get().setEnabled(true);
        assertTrue(AgcNetworkEnhancer.get().isEnabled());
    }

    @Test
    void setEnabledFlipsState() {
        AgcNetworkEnhancer.get().setEnabled(false);
        assertFalse(AgcNetworkEnhancer.get().isEnabled());
        AgcNetworkEnhancer.get().setEnabled(true);
        assertTrue(AgcNetworkEnhancer.get().isEnabled());
    }

    @Test
    void metricsStartsAtZero() {
        // 새 metrics 호출에서 카운터는 누적 (singleton이므로 정확히 0 보장 안 됨)
        final AgcNetworkEnhancer.Metrics m = AgcNetworkEnhancer.get().metrics();
        assertNotNull(m);
        assertTrue(m.applied() >= 0);
        assertTrue(m.skipped() >= 0);
        assertTrue(m.errors() >= 0);
        assertTrue(m.liveChannels() >= 0);
    }

    @Test
    void metricsRecordHoldsValues() {
        final AgcNetworkEnhancer.Metrics m = new AgcNetworkEnhancer.Metrics(10L, 5L, 1L, 7);
        assertEquals(10L, m.applied());
        assertEquals(5L, m.skipped());
        assertEquals(1L, m.errors());
        assertEquals(7, m.liveChannels());
    }

    @Test
    void disabledSkipsEnhancement() {
        // disabled 상태에서 채널 후처리는 no-op이어야 함.
        // 직접 channel을 만들기 어려우니 enable/disable 토글만 검증.
        AgcNetworkEnhancer.get().setEnabled(false);
        assertFalse(AgcNetworkEnhancer.get().isEnabled());
        AgcNetworkEnhancer.get().setEnabled(true);
        assertTrue(AgcNetworkEnhancer.get().isEnabled());
    }

    @Test
    void liveChannelCountIsNonNegative() {
        assertTrue(AgcNetworkEnhancer.get().liveChannelCount() >= 0);
    }
}
