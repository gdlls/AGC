package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AgcOptimisticTransactionManagerTest {

    @BeforeEach
    void setUp() {
        AgcOptimisticTransactionManager.get().clear();
        AgcCrossWorldQueue.get().resetMetrics();
    }

    @Test
    void testLocalTransactionCommit() {
        final AtomicInteger appliedState = new AtomicInteger(0);

        AgcPluginVirtualizer.get().runInContext("world", 100L, () -> {
            final AgcOptimisticTransactionManager.Transaction tx = AgcOptimisticTransactionManager.get().begin("test-initiator");
            assertNotNull(tx);

            AgcOptimisticTransactionManager.get().recordBlockMutation(
                "world", 1024L, 0, 42, appliedState::set
            );

            final boolean committed = AgcOptimisticTransactionManager.get().commit();
            assertTrue(committed);
            assertEquals(42, appliedState.get());
        });

        final AgcOptimisticTransactionManager.StmMetrics metrics = AgcOptimisticTransactionManager.get().metrics();
        assertEquals(1, metrics.totalCommitted());
        assertEquals(0, metrics.totalRolledBack());
    }

    @Test
    void testRemoteCrossWorldTransactionEnqueuesCrossWorldQueue() {
        final AtomicInteger appliedState = new AtomicInteger(0);

        // Caller is in "world_nether", but mutation is in "world"
        AgcPluginVirtualizer.get().runInContext("world_nether", 100L, () -> {
            AgcOptimisticTransactionManager.get().begin("cross-world-test");
            AgcOptimisticTransactionManager.get().recordBlockMutation(
                "world", 2048L, 0, 99, appliedState::set
            );

            final boolean committed = AgcOptimisticTransactionManager.get().commit();
            assertTrue(committed);

            // Mutation is remote -> queued to CrossWorldQueue, not yet executed inline
            assertEquals(0, appliedState.get());
            assertTrue(AgcCrossWorldQueue.get().hasPending());
        });

        // Drain cross-world queue at barrier
        final int drained = AgcCrossWorldQueue.get().drainAll();
        assertEquals(1, drained);
        assertEquals(99, appliedState.get());
    }

    @Test
    void testRollback() {
        final AtomicInteger appliedState = new AtomicInteger(0);

        AgcOptimisticTransactionManager.get().begin("rollback-test");
        AgcOptimisticTransactionManager.get().recordBlockMutation(
            "world", 500L, 0, 777, appliedState::set
        );
        AgcOptimisticTransactionManager.get().rollback();

        assertEquals(0, appliedState.get());
        assertNull(AgcOptimisticTransactionManager.get().currentTransaction());
        assertEquals(1, AgcOptimisticTransactionManager.get().metrics().totalRolledBack());
    }
}
