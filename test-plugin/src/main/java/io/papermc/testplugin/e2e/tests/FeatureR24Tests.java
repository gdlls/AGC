package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR24Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R24_T1_1"; }
            @Override public String getName() { return "R24 Tier 1 - Scale16 Control Law"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> law = context.checkClassExists("net.minecraft.server.AGCScale16ControlLaw");
                Object instance = law.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) law.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCScale16ControlLaw"), "Scale16 control law status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R24_T1_2"; }
            @Override public String getName() { return "R24 Tier 1 - Motion and Chunk Lookahead"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> motion = context.checkClassExists("net.minecraft.server.AGCPlayerMotionIntentModel");
                Object motionInstance = motion.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) motion.getDeclaredMethod("statusLine").invoke(motionInstance)).contains("AGCPlayerMotionIntentModel"), "Motion model status must identify itself.");
                Class<?> lookahead = context.checkClassExists("net.minecraft.server.AGCChunkLookaheadPlanner");
                Object lookaheadInstance = lookahead.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) lookahead.getDeclaredMethod("statusLine").invoke(lookaheadInstance)).contains("AGCChunkLookaheadPlanner"), "Chunk lookahead status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R24_T1_3"; }
            @Override public String getName() { return "R24 Tier 1 - Entity, Network and World Logic Algorithms"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> entity = context.checkClassExists("net.minecraft.server.AGCEntitySpatialBandIndex");
                Object entityInstance = entity.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) entity.getDeclaredMethod("statusLine").invoke(entityInstance)).contains("AGCEntitySpatialBandIndex"), "Entity spatial index status must identify itself.");
                Class<?> network = context.checkClassExists("net.minecraft.server.AGCNetworkDeltaShapePlanner");
                Object networkInstance = network.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) network.getDeclaredMethod("statusLine").invoke(networkInstance)).contains("AGCNetworkDeltaShapePlanner"), "Network delta planner status must identify itself.");
                Class<?> dag = context.checkClassExists("net.minecraft.server.AGCWorldLogicPhaseDAG");
                Object dagInstance = dag.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) dag.getDeclaredMethod("statusLine").invoke(dagInstance)).contains("AGCWorldLogicPhaseDAG"), "World DAG status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R24_T1_4"; }
            @Override public String getName() { return "R24 Tier 1 - Plugin Semantic Model and Command Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> model = context.checkClassExists("net.minecraft.server.AGCPluginSemanticModel");
                Object instance = model.getDeclaredField("INSTANCE").get(null);
                context.assertTrue(((String) model.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCPluginSemanticModel"), "Plugin semantic model status must identify itself.");
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("scale16").invoke(null);
                context.assertTrue(report.contains("AGCScale16ControlLaw") && report.contains("hardRules"), "Scale16 report must include algorithm plane and hard rules.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R24_T1_5"; }
            @Override public String getName() { return "R24 Tier 1 - Scale16 Public API"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.AGCScale16Plan");
                Class<?> performance = context.checkClassExists("io.agcmc.agc.api.AGCPerformance");
                context.assertNotNull(performance.getDeclaredMethod("scale16Plan", int.class, double.class), "scale16Plan API must exist.");
                Class<?> feature = context.checkClassExists("io.agcmc.agc.api.AGCFeature");
                context.assertNotNull(Enum.valueOf((Class<Enum>) feature.asSubclass(Enum.class), "SCALE16_CONTROL_LAW"), "AGCFeature must include scale16 control law.");
            }
        });
    }
}
