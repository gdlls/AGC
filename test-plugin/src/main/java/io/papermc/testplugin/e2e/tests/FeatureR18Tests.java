package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR18Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R18_T1_1"; }
            @Override public String getName() { return "R18 Tier 1 - Player Intent Scheduler"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> scheduler = context.checkClassExists("net.minecraft.server.AGCPlayerIntentScheduler");
                Object instance = scheduler.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(scheduler.getDeclaredMethod("snapshot").invoke(instance), "Player intent scheduler snapshot must be callable.");
                context.assertTrue(((String) scheduler.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCPlayerIntentScheduler"), "Player intent scheduler status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R18_T1_2"; }
            @Override public String getName() { return "R18 Tier 1 - Shard Planner"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> planner = context.checkClassExists("net.minecraft.server.AGCShardPlanner");
                Object instance = planner.getDeclaredField("INSTANCE").get(null);
                Object plan = planner.getDeclaredMethod("plan", int.class, int.class, int.class).invoke(instance, 2000, 128, 512);
                context.assertNotNull(plan, "Shard planner plan must be callable.");
                context.assertTrue(((String) planner.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCShardPlanner"), "Shard planner status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R18_T1_3"; }
            @Override public String getName() { return "R18 Tier 1 - Plugin Compatibility Contracts"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> contracts = context.checkClassExists("net.minecraft.server.AGCPluginCompatibilityContracts");
                Object instance = contracts.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(contracts.getDeclaredMethod("snapshot").invoke(instance), "Compatibility contracts snapshot must be callable.");
                context.assertTrue(((String) contracts.getDeclaredMethod("statusLine").invoke(instance)).contains("AGCPluginCompatibilityContracts"), "Contracts status must identify itself.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R18_T1_4"; }
            @Override public String getName() { return "R18 Tier 1 - AGC Public Compatibility API"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> api = context.checkClassExists("io.agcmc.agc.api.AGC");
                Object compatibility = api.getDeclaredMethod("compatibility").invoke(null);
                context.assertNotNull(compatibility, "AGC.compatibility() must return a service.");
                context.assertTrue(compatibility.getClass().getName().contains("AGCCompatibility"), "Compatibility service must use AGC API package.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R18_T1_5"; }
            @Override public String getName() { return "R18 Tier 1 - Scale and Contract Command Reports"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String scale = (String) reports.getDeclaredMethod("scale").invoke(null);
                String contracts = (String) reports.getDeclaredMethod("contracts").invoke(null);
                context.assertTrue(scale.contains("AGCShardPlanner"), "Scale report must include shard planner state.");
                context.assertTrue(contracts.contains("compatibility contracts"), "Contracts report must describe compatibility contracts.");
            }
        });
    }
}
