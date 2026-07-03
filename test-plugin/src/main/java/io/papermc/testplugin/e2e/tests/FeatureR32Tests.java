package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR32Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R32_T1_1"; }
            @Override public String getName() { return "R32 Tier 1 - Scale24 Hot Path Runtime and Cache"; }
            @Override public void run(TestContext context) throws Exception {
                for (String name : new String[] {
                    "net.minecraft.server.AGCScale24HotPathRuntime",
                    "net.minecraft.server.AGCHotPathPlanCache"
                }) {
                    Class<?> type = context.checkClassExists(name);
                    Object instance = type.getDeclaredField("INSTANCE").get(null);
                    context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains(type.getSimpleName()), name + " status must identify itself.");
                }
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R32_T1_2"; }
            @Override public String getName() { return "R32 Tier 1 - Network, Chunk and Entity Compilers"; }
            @Override public void run(TestContext context) throws Exception {
                for (String name : new String[] {
                    "net.minecraft.server.AGCNetworkOrderVectorCompiler",
                    "net.minecraft.server.AGCChunkHorizonCompiler",
                    "net.minecraft.server.AGCEntityObserverSetCompiler"
                }) {
                    Class<?> type = context.checkClassExists(name);
                    Object instance = type.getDeclaredField("INSTANCE").get(null);
                    context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains(type.getSimpleName()), name + " status must identify itself.");
                }
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R32_T1_3"; }
            @Override public String getName() { return "R32 Tier 1 - World, Plugin and Resource Compilers"; }
            @Override public void run(TestContext context) throws Exception {
                for (String name : new String[] {
                    "net.minecraft.server.AGCWorldReadOnlyBatchGraph",
                    "net.minecraft.server.AGCPluginDeterministicTicketCache",
                    "net.minecraft.server.AGCResourceLocalityScheduler"
                }) {
                    Class<?> type = context.checkClassExists(name);
                    Object instance = type.getDeclaredField("INSTANCE").get(null);
                    context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains(type.getSimpleName()), name + " status must identify itself.");
                }
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R32_T1_4"; }
            @Override public String getName() { return "R32 Tier 1 - Scale24 Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("scale24").invoke(null);
                context.assertTrue(report.contains("AGCScale24HotPathRuntime") && report.contains("hardRules"), "Scale24 report must include hot path runtime and hard rules.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R32_T1_5"; }
            @Override public String getName() { return "R32 Tier 1 - Scale24 Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCScale24Plan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("scale24Plan", int.class, double.class), "scale24Plan API must exist.");
            }
        });
    }
}
