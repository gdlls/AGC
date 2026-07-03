package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR20Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R20_T1_1"; }
            @Override public String getName() { return "R20 Tier 1 - Survival Scale Coordinator"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> coordinator = context.checkClassExists("net.minecraft.server.AGCSurvivalScaleCoordinator");
                Object instance = coordinator.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(coordinator.getDeclaredMethod("snapshot").invoke(instance), "Survival scale snapshot must be callable.");
                context.assertTrue(((String) coordinator.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCSurvivalScaleCoordinator"), "Survival scale status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R20_T1_2"; }
            @Override public String getName() { return "R20 Tier 1 - Resource Efficiency Engine"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> engine = context.checkClassExists("net.minecraft.server.AGCResourceEfficiencyEngine");
                Object instance = engine.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(engine.getDeclaredMethod("snapshot").invoke(instance), "Resource efficiency snapshot must be callable.");
                context.assertTrue(((String) engine.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCResourceEfficiencyEngine"), "Resource efficiency status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R20_T1_3"; }
            @Override public String getName() { return "R20 Tier 1 - Interest Graph"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> graph = context.checkClassExists("net.minecraft.server.AGCInterestGraph");
                Object instance = graph.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(graph.getDeclaredMethod("snapshot").invoke(instance), "Interest graph snapshot must be callable.");
                context.assertTrue(((String) graph.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCInterestGraph"), "Interest graph status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R20_T1_4"; }
            @Override public String getName() { return "R20 Tier 1 - Global Fairness Matrix"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> matrix = context.checkClassExists("net.minecraft.server.AGCGlobalFairnessMatrix");
                Object instance = matrix.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(matrix.getDeclaredMethod("snapshot").invoke(instance), "Global fairness snapshot must be callable.");
                context.assertTrue(((String) matrix.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCGlobalFairnessMatrix"), "Global fairness status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R20_T1_5"; }
            @Override public String getName() { return "R20 Tier 1 - Shared Read-only Cache and Commands"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> cache = context.checkClassExists("net.minecraft.server.AGCSharedReadOnlyCache");
                Object instance = cache.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(cache.getDeclaredMethod("snapshot").invoke(instance), "Shared read-only cache snapshot must be callable.");
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String scale = (String) reports.getDeclaredMethod("survivalScale").invoke(null);
                String resources = (String) reports.getDeclaredMethod("resources").invoke(null);
                context.assertTrue(scale.contains("survival scale") || scale.contains("AGCSurvivalScaleCoordinator"), "Survival scale command report must include alpha12 state.");
                context.assertTrue(resources.contains("resource") || resources.contains("AGCResourceEfficiencyEngine"), "Resources command report must include alpha12 state.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R20_T1_6"; }
            @Override public String getName() { return "R20 Tier 1 - Survival Scale Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCResourceProfile");
                context.checkClassExists("io.agcmc.agc.api.AGCSurvivalScalePlan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("resourceProfile", int.class, double.class), "resourceProfile API must exist.");
                context.assertNotNull(performance.getDeclaredMethod("survivalScalePlan", int.class, int.class, int.class, int.class), "survivalScalePlan API must exist.");
            }
        });
    }
}
