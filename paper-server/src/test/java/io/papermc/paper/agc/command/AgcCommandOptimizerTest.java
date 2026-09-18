package io.papermc.paper.agc.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcCommandOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcCommandOptimizer.get().clear();
    }

    @Test
    public void testTabCompletionThrottling() {
        AgcCommandOptimizer opt = AgcCommandOptimizer.get();
        UUID player = UUID.randomUUID();

        // 1st request -> allowed
        assertTrue(opt.canExecuteTabComplete(player));

        // Immediate 2nd request (<500ms) -> throttled
        assertFalse(opt.canExecuteTabComplete(player));

        assertEquals(1, opt.metrics().tabCompletionsAllowed());
        assertEquals(1, opt.metrics().tabCompletionsThrottled());
    }

    @Test
    public void testSelectorClamping() {
        AgcCommandOptimizer opt = AgcCommandOptimizer.get();

        // Under limit (500 <= 1000) -> 500
        assertEquals(500, opt.clampSelectorResults(500, 1000));

        // Over limit (5000 > 1000) -> 1000 clamped
        assertEquals(1000, opt.clampSelectorResults(5000, 1000));
        assertEquals(1, opt.metrics().entitySelectorClamps());
    }

    @Test
    public void testCommandTreeCaching() {
        AgcCommandOptimizer opt = AgcCommandOptimizer.get();
        UUID player = UUID.randomUUID();
        AtomicInteger buildCount = new AtomicInteger(0);

        String tree1 = opt.getOrCreateCommandTree(player, () -> {
            buildCount.incrementAndGet();
            return "CommandTreeRoot";
        });
        assertEquals("CommandTreeRoot", tree1);
        assertEquals(1, buildCount.get());

        // Second call -> cached
        String tree2 = opt.getOrCreateCommandTree(player, () -> {
            buildCount.incrementAndGet();
            return "NewTree";
        });
        assertEquals("CommandTreeRoot", tree2);
        assertEquals(1, buildCount.get());
        assertEquals(1, opt.metrics().treeCacheHits());

        // Invalidate on perm change
        opt.invalidateTreeCache(player);
        String tree3 = opt.getOrCreateCommandTree(player, () -> {
            buildCount.incrementAndGet();
            return "RefreshedTree";
        });
        assertEquals("RefreshedTree", tree3);
        assertEquals(2, buildCount.get());
    }
}
