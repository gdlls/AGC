package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR30Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R30_T1_1"; }
            @Override public String getName() { return "R30 Tier 1 - Scale22 Tick Work Compiler"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> type = context.checkClassExists("net.minecraft.server.AGCScale22TickWorkCompiler");
                Object instance = type.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCScale22TickWorkCompiler"), "Scale22 compiler status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R30_T1_2"; }
            @Override public String getName() { return "R30 Tier 1 - Network and Chunk Scale22 Algorithms"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> network = context.checkClassExists("net.minecraft.server.AGCNetworkCohortBackboneCache");
                Object networkInstance = network.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) network.getDeclaredMethod("statusLine").invoke(networkInstance)).contains("AGCNetworkCohortBackboneCache"), "Network cohort backbone status must identify itself.");
                Class<?> chunk = context.checkClassExists("net.minecraft.server.AGCChunkTerrainDemandModel");
                Object chunkInstance = chunk.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) chunk.getDeclaredMethod("statusLine").invoke(chunkInstance)).contains("AGCChunkTerrainDemandModel"), "Chunk terrain demand status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R30_T1_3"; }
            @Override public String getName() { return "R30 Tier 1 - Entity, Plugin and Multiverse Scale22 Algorithms"; }
            @Override public void run(TestContext context) throws Exception {
                for (String name : new String[] {
                    "net.minecraft.server.AGCEntityObserverMatrix",
                    "net.minecraft.server.AGCPluginOrderedCommitLedger",
                    "net.minecraft.server.AGCMultiversePhaseMatrix",
                    "net.minecraft.server.AGCSystemLoadShepherd",
                    "net.minecraft.server.AGCSemanticHotPathMeter"
                }) {
                    Class<?> type = context.checkClassExists(name);
                    Object instance = type.getDeclaredField("INSTANCE").get(null);
                    context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains(type.getSimpleName()), name + " status must identify itself.");
                }
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R30_T1_4"; }
            @Override public String getName() { return "R30 Tier 1 - Scale22 Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("scale22").invoke(null);
                context.assertTrue(report.contains("AGCScale22TickWorkCompiler") && report.contains("hardRules"), "Scale22 report must include compiler and hard rules.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R30_T1_5"; }
            @Override public String getName() { return "R30 Tier 1 - Scale22 Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCScale22Plan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("scale22Plan", int.class, double.class), "scale22Plan API must exist.");
            }
        });
    }
}
