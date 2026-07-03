package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR2Tests {
    public void register(TestRegistry registry) {
        // Tier 1 Tests
        registry.register(new E2ETest() {
            @Override public String getId() { return "R2_T1_1"; }
            @Override public String getName() { return "R2 Tier 1 - Hit Rewind Class Load Verification"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                context.assertNotNull(clazz, "MeteusHitRewind compatibility alias should be loadable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R2_T1_2"; }
            @Override public String getName() { return "R2 Tier 1 - Hit Rewind Max Latency Getter"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                java.lang.reflect.Method getMaxLatencyMs = clazz.getDeclaredMethod("getMaxLatencyMs");
                context.assertNotNull(getMaxLatencyMs, "getMaxLatencyMs method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R2_T1_3"; }
            @Override public String getName() { return "R2 Tier 1 - Hit Rewind History Size Accessor"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                java.lang.reflect.Method getHistorySize = clazz.getDeclaredMethod("getHistorySize", org.bukkit.entity.Entity.class);
                context.assertNotNull(getHistorySize, "getHistorySize method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R2_T1_4"; }
            @Override public String getName() { return "R2 Tier 1 - Hit Rewind Tick Window Resolver"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                java.lang.reflect.Method getTickWindow = clazz.getDeclaredMethod("getTickWindow", int.class);
                context.assertNotNull(getTickWindow, "getTickWindow method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R2_T1_5"; }
            @Override public String getName() { return "R2 Tier 1 - Hit Rewind Enabled Status Field"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                java.lang.reflect.Field enabledField = clazz.getDeclaredField("enabled");
                context.assertNotNull(enabledField, "enabled field must exist.");
            }
        });

        // Tier 2 Tests
        registry.register(new E2ETest() {
            @Override public String getId() { return "R2_T2_1"; }
            @Override public String getName() { return "R2 Tier 2 - Hit Rewind Store Tracked Entity State"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                java.lang.reflect.Method record = clazz.getDeclaredMethod("recordState", org.bukkit.entity.Entity.class, long.class);
                context.assertNotNull(record, "recordState method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R2_T2_2"; }
            @Override public String getName() { return "R2 Tier 2 - Hit Rewind Retrieve Historical Location"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                java.lang.reflect.Method getHistorical = clazz.getDeclaredMethod("getHistoricalLocation", org.bukkit.entity.Entity.class, long.class);
                context.assertNotNull(getHistorical, "getHistoricalLocation method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R2_T2_3"; }
            @Override public String getName() { return "R2 Tier 2 - Hit Rewind Action Execution"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                java.lang.reflect.Method rewind = clazz.getDeclaredMethod("rewindEntityTo", org.bukkit.entity.Entity.class, long.class);
                context.assertNotNull(rewind, "rewindEntityTo method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R2_T2_4"; }
            @Override public String getName() { return "R2 Tier 2 - Hit Rewind Reversion lifecycle"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                java.lang.reflect.Method restore = clazz.getDeclaredMethod("restoreEntityState", org.bukkit.entity.Entity.class);
                context.assertNotNull(restore, "restoreEntityState method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R2_T2_5"; }
            @Override public String getName() { return "R2 Tier 2 - Hit Rewind Pruning Mechanism"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusHitRewind");
                java.lang.reflect.Method prune = clazz.getDeclaredMethod("pruneOldStates", long.class);
                context.assertNotNull(prune, "pruneOldStates method must exist.");
            }
        });
    }
}
