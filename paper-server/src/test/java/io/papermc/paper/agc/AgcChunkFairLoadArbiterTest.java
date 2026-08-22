package io.papermc.paper.agc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcChunkFairLoadArbiter}.
 */
class AgcChunkFairLoadArbiterTest {

    private AgcChunkFairLoadArbiter<String> arbiter;

    @BeforeEach
    void setUp() {
        this.arbiter = new AgcChunkFairLoadArbiter<>();
    }

    @Test
    void deficitRoundRobinFairDispatchAcrossPlayers() {
        final UUID playerA = UUID.randomUUID(); // Bursting player (requests 20 chunks)
        final UUID playerB = UUID.randomUUID(); // Normal player (requests 2 chunks)

        for (int i = 0; i < 20; i++) {
            this.arbiter.enqueue(playerA, "chunk_A_" + i);
        }
        for (int i = 0; i < 2; i++) {
            this.arbiter.enqueue(playerB, "chunk_B_" + i);
        }

        assertEquals(2, this.arbiter.activeQueuedPlayers());
        assertEquals(22, this.arbiter.totalPendingChunks());

        final List<String> dispatched = new ArrayList<>();
        // In pass 1: playerA gets quantum (4 chunks), playerB gets quantum (2 chunks, queue emptied)
        final int pass1 = this.arbiter.arbitratePass(10, dispatched::add);

        assertEquals(6, pass1);
        assertEquals(6, dispatched.size());
        assertTrue(dispatched.contains("chunk_B_0"));
        assertTrue(dispatched.contains("chunk_B_1"));
        // Player B was not starved by Player A's 20-chunk burst!
    }

    @Test
    void emptyQueuesDispatchesZero() {
        final List<String> dispatched = new ArrayList<>();
        final int count = this.arbiter.arbitratePass(10, dispatched::add);
        assertEquals(0, count);
        assertEquals(0, dispatched.size());
    }
}
