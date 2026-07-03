package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR25Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R25_T1_1"; }
            @Override public String getName() { return "R25 Tier 1 - Scale17 Algorithm Kernel"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> kernel = context.checkClassExists("net.minecraft.server.AGCScale17AlgorithmKernel");
                Object instance = kernel.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) kernel.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCScale17AlgorithmKernel"), "Scale17 kernel status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R25_T1_2"; }
            @Override public String getName() { return "R25 Tier 1 - Predictive Chunk Index and Cache Locality"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> chunk = context.checkClassExists("net.minecraft.server.AGCChunkPredictiveIndex");
                Object chunkInstance = chunk.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) chunk.getDeclaredMethod("statusLine").invoke(chunkInstance)).contains("AGCChunkPredictiveIndex"), "Chunk predictive index status must identify itself.");
                Class<?> cache = context.checkClassExists("net.minecraft.server.AGCCacheLocalityWindow");
                Object cacheInstance = cache.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) cache.getDeclaredMethod("statusLine").invoke(cacheInstance)).contains("AGCCacheLocalityWindow"), "Cache locality status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R25_T1_3"; }
            @Override public String getName() { return "R25 Tier 1 - Entity Graph and Recipient Cohorts"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> entity = context.checkClassExists("net.minecraft.server.AGCEntityInteractionGraph");
                Object entityInstance = entity.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) entity.getDeclaredMethod("statusLine").invoke(entityInstance)).contains("AGCEntityInteractionGraph"), "Entity interaction graph status must identify itself.");
                Class<?> cohort = context.checkClassExists("net.minecraft.server.AGCNetworkRecipientCohortGraph");
                Object cohortInstance = cohort.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) cohort.getDeclaredMethod("statusLine").invoke(cohortInstance)).contains("AGCNetworkRecipientCohortGraph"), "Network recipient cohort graph status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R25_T1_4"; }
            @Override public String getName() { return "R25 Tier 1 - World Phase Compiler and Plugin Contract Verifier"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> world = context.checkClassExists("net.minecraft.server.AGCWorldPhaseExecutorPlan");
                Object worldInstance = world.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) world.getDeclaredMethod("statusLine").invoke(worldInstance)).contains("AGCWorldPhaseExecutorPlan"), "World phase executor status must identify itself.");
                Class<?> verifier = context.checkClassExists("net.minecraft.server.AGCPluginContractVerifier");
                Object verifierInstance = verifier.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) verifier.getDeclaredMethod("statusLine").invoke(verifierInstance)).contains("AGCPluginContractVerifier"), "Plugin contract verifier status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R25_T1_5"; }
            @Override public String getName() { return "R25 Tier 1 - Scale17 Command Report and Public API"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("scale17").invoke(null);
                context.assertTrue(report.contains("AGCScale17AlgorithmKernel") && report.contains("hardRules"), "Scale17 report must include algorithm kernel and hard rules.");
                context.checkClassExists("io.agcmc.agc.api.AGCScale17Plan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("scale17Plan", int.class, double.class), "scale17Plan API must exist.");
            }
        });
    }
}
