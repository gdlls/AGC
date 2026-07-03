package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR27Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R27_T1_1"; }
            @Override public String getName() { return "R27 Tier 1 - Scale19 Logic Kernel"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> type = context.checkClassExists("net.minecraft.server.AGCScale19LogicKernel");
                Object instance = type.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCScale19LogicKernel"), "Scale19 kernel status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R27_T1_2"; }
            @Override public String getName() { return "R27 Tier 1 - Network and Chunk Algorithms"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> network = context.checkClassExists("net.minecraft.server.AGCNetworkMulticastShapeGraph");
                Object networkInstance = network.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) network.getDeclaredMethod("statusLine").invoke(networkInstance)).contains("AGCNetworkMulticastShapeGraph"), "Network multicast status must identify itself.");
                Class<?> chunk = context.checkClassExists("net.minecraft.server.AGCChunkDemandForecastTable");
                Object chunkInstance = chunk.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) chunk.getDeclaredMethod("statusLine").invoke(chunkInstance)).contains("AGCChunkDemandForecastTable"), "Chunk forecast status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R27_T1_3"; }
            @Override public String getName() { return "R27 Tier 1 - Entity and World Phase Algorithms"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> entity = context.checkClassExists("net.minecraft.server.AGCEntityInterestSetReducer");
                Object entityInstance = entity.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) entity.getDeclaredMethod("statusLine").invoke(entityInstance)).contains("AGCEntityInterestSetReducer"), "Entity reducer status must identify itself.");
                Class<?> world = context.checkClassExists("net.minecraft.server.AGCWorldPhaseBatchCompiler");
                Object worldInstance = world.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) world.getDeclaredMethod("statusLine").invoke(worldInstance)).contains("AGCWorldPhaseBatchCompiler"), "World phase batch status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R27_T1_4"; }
            @Override public String getName() { return "R27 Tier 1 - Plugin Semantic JIT and Scale19 Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> jit = context.checkClassExists("net.minecraft.server.AGCPluginSemanticJit");
                Object jitInstance = jit.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) jit.getDeclaredMethod("statusLine").invoke(jitInstance)).contains("AGCPluginSemanticJit"), "Plugin semantic JIT status must identify itself.");
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("scale19").invoke(null);
                context.assertTrue(report.contains("AGCScale19LogicKernel") && report.contains("hardRules"), "Scale19 report must include kernel and hard rules.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R27_T1_5"; }
            @Override public String getName() { return "R27 Tier 1 - Scale19 Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCScale19Plan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("scale19Plan", int.class, double.class), "scale19Plan API must exist.");
            }
        });
    }
}
