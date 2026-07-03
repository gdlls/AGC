package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR14Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R14_T1_1"; }
            @Override public String getName() { return "R14 Tier 1 - AGC API Entry Points"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> agc = context.checkClassExists("io.agcmc.agc.api.AGC");
                context.assertNotNull(agc.getDeclaredMethod("minigames").invoke(null), "AGC.minigames must return a service.");
                context.assertNotNull(agc.getDeclaredMethod("scheduler").invoke(null), "AGC.scheduler must return a facade.");
                Object performance = agc.getDeclaredMethod("performance").invoke(null);
                context.assertNotNull(performance, "AGC.performance must return a facade.");
                context.assertNotNull(performance.getClass().getDeclaredMethod("snapshot").invoke(performance), "AGC performance snapshot must be callable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R14_T1_2"; }
            @Override public String getName() { return "R14 Tier 1 - Minigame Arena Options API"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> options = context.checkClassExists("io.agcmc.agc.api.minigame.AGCArenaOptions");
                Object builder = options.getDeclaredMethod("builder").invoke(null);
                builder.getClass().getDeclaredMethod("maxPlayers", int.class).invoke(builder, 64);
                builder.getClass().getDeclaredMethod("minPlayers", int.class).invoke(builder, 2);
                Object built = builder.getClass().getDeclaredMethod("build").invoke(builder);
                context.assertEquals(64, options.getDeclaredMethod("maxPlayers").invoke(built), "AGCArenaOptions maxPlayers builder must work.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R14_T1_3"; }
            @Override public String getName() { return "R14 Tier 1 - Minigame Events Surface"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.event.AGCPlayerJoinArenaEvent").getDeclaredMethod("getHandlerList");
                context.checkClassExists("io.agcmc.agc.api.event.AGCPlayerLeaveArenaEvent").getDeclaredMethod("getHandlerList");
                context.checkClassExists("io.agcmc.agc.api.event.AGCMatchStartEvent").getDeclaredMethod("getHandlerList");
                context.checkClassExists("io.agcmc.agc.api.event.AGCMatchEndEvent").getDeclaredMethod("getHandlerList");
                context.checkClassExists("io.agcmc.agc.api.event.AGCTeamAssignEvent").getDeclaredMethod("getHandlerList");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R14_T1_4"; }
            @Override public String getName() { return "R14 Tier 1 - Aggressive-Compatible Defaults"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> config = context.checkClassExists("io.papermc.paper.configuration.GlobalConfiguration");
                Object instance = config.getDeclaredMethod("get").invoke(null);
                Object agc = config.getDeclaredField("agc").get(instance);
                Object performance = agc.getClass().getDeclaredField("performance").get(agc);
                context.assertEquals(true, performance.getClass().getDeclaredField("packetPriorityBudgeting").get(performance), "packetPriorityBudgeting should default to aggressive-compatible true.");
                context.assertEquals(true, performance.getClass().getDeclaredField("chunkQueueBudgeting").get(performance), "chunkQueueBudgeting should default to aggressive-compatible true.");
                context.assertEquals(true, performance.getClass().getDeclaredField("adaptiveScalingProfiles").get(performance), "adaptiveScalingProfiles should default to true.");
            }
        });
    }
}
