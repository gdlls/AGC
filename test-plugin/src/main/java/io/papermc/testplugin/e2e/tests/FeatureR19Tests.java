package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR19Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R19_T1_1"; }
            @Override public String getName() { return "R19 Tier 1 - Performance Standard"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> standard = context.checkClassExists("net.minecraft.server.AGCPerformanceStandard");
                Object instance = standard.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(standard.getDeclaredMethod("snapshot").invoke(instance), "Performance standard snapshot must be callable.");
                context.assertTrue(((String) standard.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCPerformanceStandard"), "Performance standard status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R19_T1_2"; }
            @Override public String getName() { return "R19 Tier 1 - Tick Budget Arbiter"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> arbiter = context.checkClassExists("net.minecraft.server.AGCTickBudgetArbiter");
                Object instance = arbiter.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(arbiter.getDeclaredMethod("snapshot").invoke(instance), "Tick budget arbiter snapshot must be callable.");
                context.assertTrue(((String) arbiter.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCTickBudgetArbiter"), "Tick budget arbiter status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R19_T1_3"; }
            @Override public String getName() { return "R19 Tier 1 - Latency SLO"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> slo = context.checkClassExists("net.minecraft.server.AGCLatencySLO");
                Object instance = slo.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(slo.getDeclaredMethod("snapshot").invoke(instance), "Latency SLO snapshot must be callable.");
                context.assertTrue(((String) slo.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCLatencySLO"), "Latency SLO status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R19_T1_4"; }
            @Override public String getName() { return "R19 Tier 1 - Entity Spatial Snapshot Cache"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> cache = context.checkClassExists("net.minecraft.server.AGCEntitySpatialSnapshotCache");
                Object instance = cache.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(cache.getDeclaredMethod("snapshot").invoke(instance), "Entity spatial cache snapshot must be callable.");
                context.assertTrue(((String) cache.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCEntitySpatialSnapshotCache"), "Entity spatial cache status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R19_T1_5"; }
            @Override public String getName() { return "R19 Tier 1 - Mini-game Burst Planner and Hard Performance Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> planner = context.checkClassExists("net.minecraft.server.AGCMinigameBurstPlanner");
                Object instance = planner.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(planner.getDeclaredMethod("snapshot").invoke(instance), "Mini-game burst planner snapshot must be callable.");
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("hardPerformance").invoke(null);
                context.assertTrue(report.contains("aggressive performance standard") || report.contains("AGCPerformanceStandard"), "Hard performance report must include the aggressive SLO surface.");
            }
        });
    }
}
