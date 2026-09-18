package io.papermc.paper.agc.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcConnectionManagerTest {

    @BeforeEach
    public void setup() {
        AgcConnectionManager.get().clear();
    }

    @Test
    public void testOptimalWorkerThreadsScaling() {
        AgcConnectionManager manager = AgcConnectionManager.get();

        assertEquals(4, manager.computeOptimalWorkerThreads(100));
        assertEquals(4, manager.computeOptimalWorkerThreads(500));
        assertEquals(8, manager.computeOptimalWorkerThreads(1200));
        assertEquals(8, manager.computeOptimalWorkerThreads(2000));
        assertEquals(16, manager.computeOptimalWorkerThreads(3500));
    }

    @Test
    public void testBandwidthQosLimiting() {
        AgcConnectionManager manager = AgcConnectionManager.get();
        Object connection = new Object();
        manager.registerConnection(connection);

        // Send 256KB normal packet -> allowed
        assertTrue(manager.admitPacket(connection, 256 * 1024, false));

        // Send another 256KB normal packet -> total 512KB -> allowed
        assertTrue(manager.admitPacket(connection, 256 * 1024, false));

        // Send 1 more byte normal packet -> exceeds 512KB limit -> throttled
        assertFalse(manager.admitPacket(connection, 1, false));

        // High priority packet -> always allowed even if quota exceeded
        assertTrue(manager.admitPacket(connection, 64 * 1024, true));

        // Reset tick -> quota replenished
        manager.onTickStart();
        assertTrue(manager.admitPacket(connection, 256 * 1024, false));
    }

    @Test
    public void testIdleConnectionTracking() throws InterruptedException {
        AgcConnectionManager manager = AgcConnectionManager.get();
        Object conn = new Object();
        manager.registerConnection(conn);

        assertFalse(manager.isConnectionIdle(conn, 50));
        Thread.sleep(60);
        assertTrue(manager.isConnectionIdle(conn, 50));

        // Packet activity clears idle state
        manager.admitPacket(conn, 10, true);
        assertFalse(manager.isConnectionIdle(conn, 50));
    }
}
