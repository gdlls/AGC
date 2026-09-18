package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AgcNativeIoUringNetworkEngineTest {

    @BeforeEach
    void setUp() {
        AgcNativeIoUringNetworkEngine.get().clearMetrics();
    }

    @Test
    void testTransportInitializationAndChannelRegistration() {
        assertNotNull(AgcNativeIoUringNetworkEngine.get().getTransportType());

        AgcNativeIoUringNetworkEngine.get().initialize();
        assertTrue(AgcNativeIoUringNetworkEngine.get().isInitialized());

        AgcNativeIoUringNetworkEngine.get().registerChannel();
        AgcNativeIoUringNetworkEngine.get().registerChannel();
        AgcNativeIoUringNetworkEngine.get().recordPacketDispatch(256);

        final AgcNativeIoUringNetworkEngine.NetworkEngineMetrics metrics =
            AgcNativeIoUringNetworkEngine.get().metrics();

        assertEquals(2, metrics.activeChannels());
        assertEquals(1, metrics.totalPacketsDispatched());
        assertEquals(256, metrics.totalBytesTransferred());

        AgcNativeIoUringNetworkEngine.get().unregisterChannel();
        assertEquals(1, AgcNativeIoUringNetworkEngine.get().metrics().activeChannels());
    }
}
