package io.papermc.paper.agc.scoreboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcScoreboardOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcScoreboardOptimizer.get().clear();
    }

    @Test
    public void testScoreDeduplicationAndBatching() {
        AgcScoreboardOptimizer opt = AgcScoreboardOptimizer.get();

        // 1st change: p1 = 100 -> queued
        assertTrue(opt.submitScoreUpdate("kills", "Player1", 100));

        // Redundant change: p1 = 100 again -> filtered
        assertFalse(opt.submitScoreUpdate("kills", "Player1", 100));

        // 2nd change: p2 = 50 -> queued
        assertTrue(opt.submitScoreUpdate("kills", "Player2", 50));

        assertEquals(3, opt.metrics().scoreUpdatesSubmitted());
        assertEquals(1, opt.metrics().redundantUpdatesFiltered());

        // Flush
        List<AgcScoreboardOptimizer.ScoreboardEntry> flushed = opt.flushPendingUpdates("kills");
        assertEquals(2, flushed.size());
        assertEquals("Player1", flushed.get(0).scoreHolder());
        assertEquals(100, flushed.get(0).score());
        assertEquals("Player2", flushed.get(1).scoreHolder());
        assertEquals(50, flushed.get(1).score());
        assertEquals(1, opt.metrics().batchPacketsDispatched());

        // Subsequent flush -> empty
        assertTrue(opt.flushPendingUpdates("kills").isEmpty());
    }
}
