package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR26Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R26_T1_1"; }
            @Override public String getName() { return "R26 Tier 1 - Unified Tick Plan Compiler"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> type = context.checkClassExists("net.minecraft.server.AGCUnifiedTickPlanCompiler");
                Object instance = type.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCUnifiedTickPlanCompiler"), "Unified tick planner status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R26_T1_2"; }
            @Override public String getName() { return "R26 Tier 1 - Chunk Route and Entity Delta Algorithms"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> chunk = context.checkClassExists("net.minecraft.server.AGCChunkSpatialRoutePlanner");
                Object chunkInstance = chunk.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) chunk.getDeclaredMethod("statusLine").invoke(chunkInstance)).contains("AGCChunkSpatialRoutePlanner"), "Chunk route status must identify itself.");
                Class<?> entity = context.checkClassExists("net.minecraft.server.AGCEntityTrackerDeltaIndex");
                Object entityInstance = entity.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) entity.getDeclaredMethod("statusLine").invoke(entityInstance)).contains("AGCEntityTrackerDeltaIndex"), "Entity delta status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R26_T1_3"; }
            @Override public String getName() { return "R26 Tier 1 - Network Send Graph and Read-only Worker Planner"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> network = context.checkClassExists("net.minecraft.server.AGCNetworkSendGraphCompiler");
                Object networkInstance = network.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) network.getDeclaredMethod("statusLine").invoke(networkInstance)).contains("AGCNetworkSendGraphCompiler"), "Network graph status must identify itself.");
                Class<?> planner = context.checkClassExists("net.minecraft.server.AGCReadOnlyWorkStealingPlanner");
                Object plannerInstance = planner.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) planner.getDeclaredMethod("statusLine").invoke(plannerInstance)).contains("AGCReadOnlyWorkStealingPlanner"), "Read-only work planner status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R26_T1_4"; }
            @Override public String getName() { return "R26 Tier 1 - Plugin Firewall and Scale18 Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> firewall = context.checkClassExists("net.minecraft.server.AGCPluginSemanticFirewall");
                Object firewallInstance = firewall.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) firewall.getDeclaredMethod("statusLine").invoke(firewallInstance)).contains("AGCPluginSemanticFirewall"), "Plugin firewall status must identify itself.");
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("scale18").invoke(null);
                context.assertTrue(report.contains("AGCScale18ControlPlane") && report.contains("hardRules"), "Scale18 report must include control plane and hard rules.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R26_T1_5"; }
            @Override public String getName() { return "R26 Tier 1 - Scale18 Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCScale18Plan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("scale18Plan", int.class, double.class), "scale18Plan API must exist.");
            }
        });
    }
}
