package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR22Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R22_T1_1"; }
            @Override public String getName() { return "R22 Tier 1 - Alpha14 Scale Kernel"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> kernel = context.checkClassExists("net.minecraft.server.AGCScaleKernel");
                Object instance = kernel.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(kernel.getDeclaredMethod("snapshot").invoke(instance), "Scale kernel snapshot must be callable.");
                context.assertTrue(((String) kernel.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCScaleKernel"), "Scale kernel status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R22_T1_2"; }
            @Override public String getName() { return "R22 Tier 1 - Region Hotspot and Network Fanout Kernels"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> hotspot = context.checkClassExists("net.minecraft.server.AGCRegionHotspotMap");
                Object hotspotInstance = hotspot.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) hotspot.getDeclaredMethod("statusLine").invoke(hotspotInstance)).contains("AGCRegionHotspotMap"), "Hotspot status must identify itself.");
                Class<?> fanout = context.checkClassExists("net.minecraft.server.AGCNetworkFanoutKernel");
                Object fanoutInstance = fanout.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) fanout.getDeclaredMethod("statusLine").invoke(fanoutInstance)).contains("AGCNetworkFanoutKernel"), "Network fanout status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R22_T1_3"; }
            @Override public String getName() { return "R22 Tier 1 - Chunk Pipeline and Entity Tracker Kernels"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> chunk = context.checkClassExists("net.minecraft.server.AGCChunkPipelineKernel");
                Object chunkInstance = chunk.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) chunk.getDeclaredMethod("statusLine").invoke(chunkInstance)).contains("AGCChunkPipelineKernel"), "Chunk pipeline status must identify itself.");
                Class<?> entity = context.checkClassExists("net.minecraft.server.AGCEntityTrackerKernel");
                Object entityInstance = entity.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) entity.getDeclaredMethod("statusLine").invoke(entityInstance)).contains("AGCEntityTrackerKernel"), "Entity tracker kernel status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R22_T1_4"; }
            @Override public String getName() { return "R22 Tier 1 - Thread and Plugin Logic Translators"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> affinity = context.checkClassExists("net.minecraft.server.AGCThreadAffinityTranslator");
                Object affinityInstance = affinity.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) affinity.getDeclaredMethod("statusLine").invoke(affinityInstance)).contains("AGCThreadAffinityTranslator"), "Affinity translator status must identify itself.");
                Class<?> plugin = context.checkClassExists("net.minecraft.server.AGCPluginLogicTranslator");
                Object pluginInstance = plugin.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) plugin.getDeclaredMethod("statusLine").invoke(pluginInstance)).contains("AGCPluginLogicTranslator"), "Plugin logic translator status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R22_T1_5"; }
            @Override public String getName() { return "R22 Tier 1 - Wild Scale Command Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("wildScale").invoke(null);
                context.assertTrue(report.contains("AGCScaleKernel") && report.contains("hardRules"), "Wild scale report must include alpha14 kernel and hard rules.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R22_T1_6"; }
            @Override public String getName() { return "R22 Tier 1 - Wild Scale Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCWildScalePlan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("wildScalePlan", int.class, double.class), "wildScalePlan API must exist.");
                Class<?> feature = context.checkClassExists("io.agcmc.agc.api.AGCFeature");
                context.assertNotNull(Enum.valueOf((Class<Enum>) feature.asSubclass(Enum.class), "SCALE_KERNEL"), "AGCFeature must include alpha14 scale kernel.");
            }
        });
    }
}
