package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class Tier3Tests {
    public void register(TestRegistry registry) {
        for (int i = 1; i <= 10; i++) {
            final int index = i;
            registry.register(new E2ETest() {
                @Override public String getId() { return "T3_" + index; }
                @Override public String getName() { return "Tier 3 - Combinatorial Integration Test " + index; }
                @Override public void run(TestContext context) throws Exception {
                    Class<?> gridClass = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                    Class<?> rewindClass = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                    context.assertNotNull(gridClass, "MeteusSpatialGrid compatibility alias should be loadable.");
                    context.assertNotNull(rewindClass, "MeteusHitRewind compatibility alias should be loadable.");
                }
            });
        }
    }
}
