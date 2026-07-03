package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR15Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R15_T1_1"; }
            @Override public String getName() { return "R15 Tier 1 - AGC Optimisation Envelope Runtime"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> envelope = context.checkClassExists("net.minecraft.server.AGCOptimizationEnvelope");
                Object instance = envelope.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(envelope.getDeclaredMethod("snapshot").invoke(instance), "AGC optimisation envelope snapshot must be callable.");
                context.assertNotNull(envelope.getDeclaredMethod("statusLine").invoke(instance), "AGC optimisation envelope status line must be callable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R15_T1_2"; }
            @Override public String getName() { return "R15 Tier 1 - Entity Admission Runtime"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> admission = context.checkClassExists("net.minecraft.server.AGCEntityAdmission");
                Object instance = admission.getDeclaredField("INSTANCE").get(null);
                context.assertNotNull(admission.getDeclaredMethod("snapshot").invoke(instance), "AGC entity admission snapshot must be callable.");
                context.assertNotNull(admission.getDeclaredMethod("statusLine").invoke(instance), "AGC entity admission status line must be callable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R15_T1_3"; }
            @Override public String getName() { return "R15 Tier 1 - Minigame Profile API"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> profile = context.checkClassExists("io.agcmc.agc.api.minigame.AGCMinigameProfile");
                Object duel = Enum.valueOf((Class<Enum>) profile.asSubclass(Enum.class), "DUEL");
                context.assertNotNull(profile.getDeclaredMethod("recommendedMaxPlayers").invoke(duel), "AGC minigame profile max players must be exposed.");
                Class<?> options = context.checkClassExists("io.agcmc.agc.api.minigame.AGCArenaOptions");
                Object builder = options.getDeclaredMethod("builder").invoke(null);
                builder.getClass().getDeclaredMethod("profile", profile).invoke(builder, duel);
                Object built = builder.getClass().getDeclaredMethod("build").invoke(builder);
                context.assertEquals(duel, options.getDeclaredMethod("profile").invoke(built), "Arena options profile must round-trip.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R15_T1_4"; }
            @Override public String getName() { return "R15 Tier 1 - Minigame Extra Events Surface"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("io.agcmc.agc.api.event.AGCArenaPrepareEvent").getDeclaredMethod("getHandlerList");
                context.checkClassExists("io.agcmc.agc.api.event.AGCArenaResetEvent").getDeclaredMethod("getHandlerList");
                context.checkClassExists("io.agcmc.agc.api.event.AGCPlayerSpectateArenaEvent").getDeclaredMethod("getHandlerList");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R15_T1_5"; }
            @Override public String getName() { return "R15 Tier 1 - Safety Command Report"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> reports = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                String report = (String) reports.getDeclaredMethod("safety").invoke(null);
                context.assertTrue(report.contains("AGC safety envelope"), "AGC safety report must include envelope title.");
            }
        });
    }
}
