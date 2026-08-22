package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR23Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R23_T1_1"; }
            @Override public String getName() { return "R23 Tier 1 - Alpha15 Scale Control Plane"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> plane = context.checkClassExists("net.minecraft.server.AGCScaleControlPlane");
                Object instance = plane.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(plane.getDeclaredMethod("snapshot").invoke(instance), "Scale control snapshot must be callable.");
                context.assertTrue(((String) plane.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCScaleControlPlane"), "Scale control status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R23_T1_2"; }
            @Override public String getName() { return "R23 Tier 1 - Packet Shape and Encoding Planners"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> shape = context.checkClassExists("net.minecraft.server.AGCPacketShapeTable");
                Object shapeInstance = shape.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) shape.getDeclaredMethod("statusLine").invoke(shapeInstance)).contains("AGCPacketShapeTable"), "Packet shape status must identify itself.");
                Class<?> encoding = context.checkClassExists("net.minecraft.server.AGCEncodingReusePlanner");
                Object encodingInstance = encoding.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) encoding.getDeclaredMethod("statusLine").invoke(encodingInstance)).contains("AGCEncodingReusePlanner"), "Encoding planner status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R23_T1_3"; }
            @Override public String getName() { return "R23 Tier 1 - Cohort, Chunk Compactor and Entity Banding"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> cohort = context.checkClassExists("net.minecraft.server.AGCPlayerCohortTable");
                Object cohortInstance = cohort.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) cohort.getDeclaredMethod("statusLine").invoke(cohortInstance)).contains("AGCPlayerCohortTable"), "Cohort status must identify itself.");
                Class<?> chunk = context.checkClassExists("net.minecraft.server.AGCChunkIntentCompactor");
                Object chunkInstance = chunk.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) chunk.getDeclaredMethod("statusLine").invoke(chunkInstance)).contains("AGCChunkIntentCompactor"), "Chunk compactor status must identify itself.");
                Class<?> entity = context.checkClassExists("net.minecraft.server.AGCEntityBandingPlanner");
                Object entityInstance = entity.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) entity.getDeclaredMethod("statusLine").invoke(entityInstance)).contains("AGCEntityBandingPlanner"), "Entity banding status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R23_T1_4"; }
            @Override public String getName() { return "R23 Tier 1 - Memory, Plugin Backpressure and Hotspot Eviction"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> memory = context.checkClassExists("net.minecraft.server.AGCMemoryLocalityPlanner");
                Object memoryInstance = memory.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) memory.getDeclaredMethod("statusLine").invoke(memoryInstance)).contains("AGCMemoryLocalityPlanner"), "Memory planner status must identify itself.");
                Class<?> plugin = context.checkClassExists("net.minecraft.server.AGCPluginBackpressureBridge");
                Object pluginInstance = plugin.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) plugin.getDeclaredMethod("statusLine").invoke(pluginInstance)).contains("AGCPluginBackpressureBridge"), "Plugin backpressure status must identify itself.");
                Class<?> hotspot = context.checkClassExists("net.minecraft.server.AGCHotspotEvictionPlanner");
                Object hotspotInstance = hotspot.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) hotspot.getDeclaredMethod("statusLine").invoke(hotspotInstance)).contains("AGCHotspotEvictionPlanner"), "Hotspot eviction status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R23_T1_5"; }
            @Override public String getName() { return "R23 Tier 1 - Scale15 Command Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("scale15").invoke(null);
                context.assertTrue(report.contains("AGCScaleControlPlane") && report.contains("hardRules"), "Scale15 report must include alpha15 plane and hard rules.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R23_T1_6"; }
            @Override public String getName() { return "R23 Tier 1 - Scale15 Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCScalePlan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("scale15Plan", int.class, double.class), "scale15Plan API must exist.");
                Class<?> feature = context.checkClassExists("io.agcmc.agc.api.AGCFeature");
                context.assertNotNull(Enum.valueOf((Class<Enum>) feature.asSubclass(Enum.class), "SCALE_CONTROL_PLANE"), "AGCFeature must include alpha15 scale control plane.");
            }
        });
    }
}
