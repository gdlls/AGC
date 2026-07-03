package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR29Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R29_T1_1"; }
            @Override public String getName() { return "R29 Tier 1 - Scale21 Critical Path Runtime"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> type = context.checkClassExists("net.minecraft.server.AGCScale21CriticalPathRuntime");
                Object instance = type.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) type.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCScale21CriticalPathRuntime"), "Scale21 runtime status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R29_T1_2"; }
            @Override public String getName() { return "R29 Tier 1 - Cluster and Chunk Storm Algorithms"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> cluster = context.checkClassExists("net.minecraft.server.AGCPlayerClusterMovementPlanner");
                Object clusterInstance = cluster.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) cluster.getDeclaredMethod("statusLine").invoke(clusterInstance)).contains("AGCPlayerClusterMovementPlanner"), "Cluster planner status must identify itself.");
                Class<?> storm = context.checkClassExists("net.minecraft.server.AGCChunkStormController");
                Object stormInstance = storm.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) storm.getDeclaredMethod("statusLine").invoke(stormInstance)).contains("AGCChunkStormController"), "Chunk storm controller status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R29_T1_3"; }
            @Override public String getName() { return "R29 Tier 1 - Entity Density and Broadcast Planner"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> entity = context.checkClassExists("net.minecraft.server.AGCEntityDensityField");
                Object entityInstance = entity.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) entity.getDeclaredMethod("statusLine").invoke(entityInstance)).contains("AGCEntityDensityField"), "Entity density status must identify itself.");
                Class<?> broadcast = context.checkClassExists("net.minecraft.server.AGCNetworkBroadcastPlanner");
                Object broadcastInstance = broadcast.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) broadcast.getDeclaredMethod("statusLine").invoke(broadcastInstance)).contains("AGCNetworkBroadcastPlanner"), "Broadcast planner status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R29_T1_4"; }
            @Override public String getName() { return "R29 Tier 1 - Plugin Sequencer, Multiverse Lanes and Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> sequencer = context.checkClassExists("net.minecraft.server.AGCPluginCommitSequencer");
                Object seqInstance = sequencer.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) sequencer.getDeclaredMethod("statusLine").invoke(seqInstance)).contains("AGCPluginCommitSequencer"), "Plugin sequencer status must identify itself.");
                Class<?> lanes = context.checkClassExists("net.minecraft.server.AGCMultiverseLaneAllocator");
                Object laneInstance = lanes.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) lanes.getDeclaredMethod("statusLine").invoke(laneInstance)).contains("AGCMultiverseLaneAllocator"), "Multiverse lane allocator status must identify itself.");
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("scale21").invoke(null);
                context.assertTrue(report.contains("AGCScale21CriticalPathRuntime") && report.contains("hardRules"), "Scale21 report must include runtime and hard rules.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R29_T1_5"; }
            @Override public String getName() { return "R29 Tier 1 - Scale21 Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCScale21Plan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("scale21Plan", int.class, double.class), "scale21Plan API must exist.");
            }
        });
    }
}
