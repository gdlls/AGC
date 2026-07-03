package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR31Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R31_T1_1"; }
            @Override public String getName() { return "R31 Tier 1 - Scale23 Dispatch Table"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> type = context.checkClassExists("net.minecraft.server.AGCScale23DispatchTable");
                Object instance = type.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCScale23DispatchTable"), "Scale23 dispatch status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R31_T1_2"; }
            @Override public String getName() { return "R31 Tier 1 - Network Diff and Chunk Frontier"; }
            @Override public void run(TestContext context) throws Exception {
                for (String name : new String[] {
                    "net.minecraft.server.AGCNetworkStableDiffPlanner",
                    "net.minecraft.server.AGCChunkFrontierScheduler"
                }) {
                    Class<?> type = context.checkClassExists(name);
                    Object instance = type.getDeclaredField("INSTANCE").get(null);
                    context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains(type.getSimpleName()), name + " status must identify itself.");
                }
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R31_T1_3"; }
            @Override public String getName() { return "R31 Tier 1 - Entity, Plugin, World and Worker Algorithms"; }
            @Override public void run(TestContext context) throws Exception {
                for (String name : new String[] {
                    "net.minecraft.server.AGCEntityVisibilityLattice",
                    "net.minecraft.server.AGCPluginCommitCoalescer",
                    "net.minecraft.server.AGCWorldBarrierDAGCompiler",
                    "net.minecraft.server.AGCWorkerRoutePlanner"
                }) {
                    Class<?> type = context.checkClassExists(name);
                    Object instance = type.getDeclaredField("INSTANCE").get(null);
                    context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains(type.getSimpleName()), name + " status must identify itself.");
                }
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R31_T1_4"; }
            @Override public String getName() { return "R31 Tier 1 - Scale23 Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("scale23").invoke(null);
                context.assertTrue(report.contains("AGCScale23DispatchTable") && report.contains("hardRules"), "Scale23 report must include dispatch table and hard rules.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R31_T1_5"; }
            @Override public String getName() { return "R31 Tier 1 - Scale23 Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCScale23Plan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("scale23Plan", int.class, double.class), "scale23Plan API must exist.");
            }
        });
    }
}
