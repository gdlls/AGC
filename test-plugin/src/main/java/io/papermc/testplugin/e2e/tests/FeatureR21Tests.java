package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR21Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R21_T1_1"; }
            @Override public String getName() { return "R21 Tier 1 - Compute Topology Planner"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> planner = context.checkClassExists("net.minecraft.server.AGCComputeTopologyPlanner");
                Object instance = planner.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(planner.getDeclaredMethod("snapshot").invoke(instance), "Compute topology snapshot must be callable.");
                context.assertTrue(((String) planner.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCComputeTopologyPlanner"), "Compute topology status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R21_T1_2"; }
            @Override public String getName() { return "R21 Tier 1 - Fanout Deduplicator"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> dedupe = context.checkClassExists("net.minecraft.server.AGCFanoutDeduplicator");
                Object instance = dedupe.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(dedupe.getDeclaredMethod("snapshot").invoke(instance), "Fanout deduplicator snapshot must be callable.");
                context.assertTrue(((String) dedupe.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCFanoutDeduplicator"), "Fanout deduplicator status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R21_T1_3"; }
            @Override public String getName() { return "R21 Tier 1 - Chunk Intent Planner"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> planner = context.checkClassExists("net.minecraft.server.AGCChunkIntentPlanner");
                Object instance = planner.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(planner.getDeclaredMethod("snapshot").invoke(instance), "Chunk intent planner snapshot must be callable.");
                context.assertTrue(((String) planner.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCChunkIntentPlanner"), "Chunk intent planner status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R21_T1_4"; }
            @Override public String getName() { return "R21 Tier 1 - Entity Visibility Preselector"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> selector = context.checkClassExists("net.minecraft.server.AGCEntityVisibilityPreselector");
                Object instance = selector.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(selector.getDeclaredMethod("snapshot").invoke(instance), "Entity visibility preselector snapshot must be callable.");
                context.assertTrue(((String) selector.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCEntityVisibilityPreselector"), "Entity visibility status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R21_T1_5"; }
            @Override public String getName() { return "R21 Tier 1 - Storage IO Governor and Topology Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> governor = context.checkClassExists("net.minecraft.server.AGCStorageIoGovernor");
                Object instance = governor.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(governor.getDeclaredMethod("snapshot").invoke(instance), "Storage IO governor snapshot must be callable.");
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String topology = (String) reports.getDeclaredMethod("topology").invoke(null);
                context.assertTrue(topology.contains("AGCComputeTopologyPlanner") && topology.contains("AGCFanoutDeduplicator"), "Topology report must include alpha13 planners.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R21_T1_6"; }
            @Override public String getName() { return "R21 Tier 1 - Compute Efficiency Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCComputeEfficiencyPlan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("computeEfficiencyPlan", int.class, int.class), "computeEfficiencyPlan API must exist.");
                Class<?> feature = context.checkClassExists("io.agcmc.agc.api.AGCFeature");
                context.assertNotNull(Enum.valueOf((Class<Enum>) feature.asSubclass(Enum.class), "COMPUTE_TOPOLOGY_PLANNER"), "AGCFeature must include alpha13 compute topology.");
            }
        });
    }
}
