package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR28Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R28_T1_1"; }
            @Override public String getName() { return "R28 Tier 1 - Scale20 Lossless Pipeline"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> type = context.checkClassExists("net.minecraft.server.AGCScale20LosslessPipeline");
                Object instance = type.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCScale20LosslessPipeline"), "Scale20 pipeline status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R28_T1_2"; }
            @Override public String getName() { return "R28 Tier 1 - Network Backbone and Chunk Worksets"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> network = context.checkClassExists("net.minecraft.server.AGCNetworkBackboneGraph");
                Object networkInstance = network.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) network.getDeclaredMethod("statusLine").invoke(networkInstance)).contains("AGCNetworkBackboneGraph"), "Network backbone status must identify itself.");
                Class<?> chunk = context.checkClassExists("net.minecraft.server.AGCChunkWorksetSlicer");
                Object chunkInstance = chunk.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) chunk.getDeclaredMethod("statusLine").invoke(chunkInstance)).contains("AGCChunkWorksetSlicer"), "Chunk workset status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R28_T1_3"; }
            @Override public String getName() { return "R28 Tier 1 - Entity Witness and World Scheduler"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> entity = context.checkClassExists("net.minecraft.server.AGCEntityWitnessIndex");
                Object entityInstance = entity.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) entity.getDeclaredMethod("statusLine").invoke(entityInstance)).contains("AGCEntityWitnessIndex"), "Entity witness status must identify itself.");
                Class<?> world = context.checkClassExists("net.minecraft.server.AGCWorldSemanticScheduler");
                Object worldInstance = world.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) world.getDeclaredMethod("statusLine").invoke(worldInstance)).contains("AGCWorldSemanticScheduler"), "World scheduler status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R28_T1_4"; }
            @Override public String getName() { return "R28 Tier 1 - Plugin Determinism and Scale20 Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> guard = context.checkClassExists("net.minecraft.server.AGCPluginDeterminismGuard");
                Object guardInstance = guard.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) guard.getDeclaredMethod("statusLine").invoke(guardInstance)).contains("AGCPluginDeterminismGuard"), "Plugin guard status must identify itself.");
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("scale20").invoke(null);
                context.assertTrue(report.contains("AGCScale20LosslessPipeline") && report.contains("hardRules"), "Scale20 report must include pipeline and hard rules.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R28_T1_5"; }
            @Override public String getName() { return "R28 Tier 1 - Scale20 Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCScale20Plan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("scale20Plan", int.class, double.class), "scale20Plan API must exist.");
            }
        });
    }
}
