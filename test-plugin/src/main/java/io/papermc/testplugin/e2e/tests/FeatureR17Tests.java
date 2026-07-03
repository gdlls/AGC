package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR17Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R17_T1_1"; }
            @Override public String getName() { return "R17 Tier 1 - No-Invasion Optimizer"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> optimizer = context.checkClassExists("net.minecraft.server.AGCNoInvasionOptimizer");
                Object instance = optimizer.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(optimizer.getDeclaredMethod("snapshot").invoke(instance), "No-invasion optimizer snapshot must be callable.");
                String status = (String) optimizer.getDeclaredMethod("statusLine").invoke(instance);
                context.assertTrue(status.contains("AGCNoInvasionOptimizer"), "No-invasion status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R17_T1_2"; }
            @Override public String getName() { return "R17 Tier 1 - Chunk FIFO Fair Queue"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> queue = context.checkClassExists("net.minecraft.server.AGCChunkFairQueue");
                Object instance = queue.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(queue.getDeclaredMethod("snapshot").invoke(instance), "Chunk fair queue snapshot must be callable.");
                context.assertTrue(((String) queue.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCChunkFairQueue"), "Chunk fair queue status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R17_T1_3"; }
            @Override public String getName() { return "R17 Tier 1 - Entity Snapshot Planner"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> planner = context.checkClassExists("net.minecraft.server.AGCEntitySnapshotPlanner");
                Object instance = planner.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(planner.getDeclaredMethod("snapshot").invoke(instance), "Entity snapshot planner snapshot must be callable.");
                context.assertTrue(((String) planner.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCEntitySnapshotPlanner"), "Entity snapshot planner status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R17_T1_4"; }
            @Override public String getName() { return "R17 Tier 1 - World Write Intent Graph"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> graph = context.checkClassExists("net.minecraft.server.AGCWorldWriteIntentGraph");
                Object instance = graph.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(graph.getDeclaredMethod("lastPlan").invoke(instance), "World write intent graph last plan must be callable.");
                context.assertTrue(((String) graph.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCWorldWriteIntentGraph"), "World write intent graph status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R17_T1_5"; }
            @Override public String getName() { return "R17 Tier 1 - No-Invasion Command Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("noInvasion").invoke(null);
                context.assertTrue(report.contains("AGCNoInvasionOptimizer"), "No-invasion report must include optimizer status.");
                context.assertTrue(report.contains("AGCWorldWriteIntentGraph"), "No-invasion report must include write intent graph status.");
            }
        });
    }
}
