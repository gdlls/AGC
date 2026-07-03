package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR16Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R16_T1_1"; }
            @Override public String getName() { return "R16 Tier 1 - Semantic Invariant Ledger"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> invariant = context.checkClassExists("net.minecraft.server.AGCSemanticInvariant");
                Object instance = invariant.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(invariant.getDeclaredMethod("snapshot").invoke(instance), "AGC semantic invariant snapshot must be callable.");
                String status = (String) invariant.getDeclaredMethod("statusLine").invoke(instance);
                context.assertTrue(status.contains("AGCSemanticInvariant"), "Semantic invariant status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R16_T1_2"; }
            @Override public String getName() { return "R16 Tier 1 - Deterministic Task Pipeline"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> pipeline = context.checkClassExists("net.minecraft.server.AGCDeterministicTaskPipeline");
                Object instance = pipeline.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(pipeline.getDeclaredMethod("snapshot").invoke(instance), "Deterministic pipeline snapshot must be callable.");
                context.assertNotNull(pipeline.getDeclaredMethod("statusLine").invoke(instance), "Deterministic pipeline status must be callable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R16_T1_3"; }
            @Override public String getName() { return "R16 Tier 1 - World Concurrency Planner"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> planner = context.checkClassExists("net.minecraft.server.AGCWorldConcurrencyPlanner");
                Object instance = planner.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(planner.getDeclaredMethod("lastPlan").invoke(instance), "World concurrency planner last plan must be callable.");
                String status = (String) planner.getDeclaredMethod("statusLine").invoke(instance);
                context.assertTrue(status.contains("AGCWorldConcurrencyPlanner"), "World planner status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R16_T1_4"; }
            @Override public String getName() { return "R16 Tier 1 - Semantics Command Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("safety").invoke(null);
                context.assertTrue(report.contains("AGCSemanticInvariant"), "Safety report must include semantic invariant status.");
                context.assertTrue(report.contains("AGCWorldConcurrencyPlanner"), "Safety report must include world planner status.");
            }
        });
    }
}
