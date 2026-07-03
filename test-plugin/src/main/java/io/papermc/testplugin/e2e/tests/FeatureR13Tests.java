package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR13Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R13_T1_1"; }
            @Override public String getName() { return "R13 Tier 1 - Adaptive Scaling Controller"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.AGCScalingController");
                java.lang.reflect.Field instance = clazz.getDeclaredField("INSTANCE");
                Object controller = instance.get(null);
                java.lang.reflect.Method status = clazz.getDeclaredMethod("statusLine");
                context.assertNotNull(status.invoke(controller), "AGCScalingController statusLine must be callable.");
                java.lang.reflect.Method sample = clazz.getDeclaredMethod("sampleAndApply", double.class, long.class, long.class);
                context.assertNotNull(sample.invoke(controller, 50.0D, 0L, 0L), "AGCScalingController sampleAndApply must return a profile.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R13_T1_2"; }
            @Override public String getName() { return "R13 Tier 1 - Stability Journal"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.AGCStabilityJournal");
                java.lang.reflect.Field instance = clazz.getDeclaredField("INSTANCE");
                Object journal = instance.get(null);
                java.lang.reflect.Method record = clazz.getDeclaredMethod("record", String.class, String.class);
                record.invoke(journal, "test", "feature-r13");
                java.lang.reflect.Method dump = clazz.getDeclaredMethod("dump", int.class);
                Object result = dump.invoke(journal, 5);
                context.assertNotNull(result, "AGCStabilityJournal dump must be callable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R13_T1_3"; }
            @Override public String getName() { return "R13 Tier 1 - Network Fail-Open Limits"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.AGCNetworkAdmission");
                java.lang.reflect.Field instance = clazz.getDeclaredField("INSTANCE");
                Object admission = instance.get(null);
                java.lang.reflect.Method configure = clazz.getDeclaredMethod("configureDeferredFlushLimits", int.class, int.class);
                configure.invoke(admission, 8, 8);
                java.lang.reflect.Method status = clazz.getDeclaredMethod("statusLine");
                context.assertNotNull(status.invoke(admission), "AGCNetworkAdmission status after deferred-limit configure must be callable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R13_T1_4"; }
            @Override public String getName() { return "R13 Tier 1 - AGC Profile Reports"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.AGCCommandReports");
                java.lang.reflect.Method profile = clazz.getDeclaredMethod("profile");
                java.lang.reflect.Method journal = clazz.getDeclaredMethod("journal");
                context.assertNotNull(profile.invoke(null), "AGCCommandReports profile must be callable.");
                context.assertNotNull(journal.invoke(null), "AGCCommandReports journal must be callable.");
            }
        });
    }
}
