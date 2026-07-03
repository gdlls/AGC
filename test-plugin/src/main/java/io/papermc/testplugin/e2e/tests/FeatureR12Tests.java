package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR12Tests {
    public void register(TestRegistry registry) {
        registry.register(new E2ETest() {
            @Override public String getId() { return "R12_T1_1"; }
            @Override public String getName() { return "R12 Tier 1 - Runtime Health Drain Surface"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.AGCRuntimeHealth");
                java.lang.reflect.Field instance = clazz.getDeclaredField("INSTANCE");
                Object health = instance.get(null);
                java.lang.reflect.Method status = clazz.getDeclaredMethod("statusLine");
                context.assertNotNull(status.invoke(health), "AGCRuntimeHealth statusLine must be callable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R12_T1_2"; }
            @Override public String getName() { return "R12 Tier 1 - Network Deferred Flush Drain"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.AGCNetworkAdmission");
                java.lang.reflect.Field instance = clazz.getDeclaredField("INSTANCE");
                Object admission = instance.get(null);
                java.lang.reflect.Method drain = clazz.getDeclaredMethod("drainDeferredFlushes");
                context.assertNotNull(drain.invoke(admission), "AGCNetworkAdmission drainDeferredFlushes must be callable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R12_T1_3"; }
            @Override public String getName() { return "R12 Tier 1 - Chunk Load/Generate Admission"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> admissionClazz = context.checkClassExists("net.minecraft.server.AGCChunkAdmission");
                Class<?> budgetClazz = context.checkClassExists("net.minecraft.server.AGCChunkBudget");
                Class<?> operationClazz = null;
                for (Class<?> nested : budgetClazz.getDeclaredClasses()) {
                    if (nested.getSimpleName().equals("Operation")) {
                        operationClazz = nested;
                        break;
                    }
                }
                context.assertNotNull(operationClazz, "AGCChunkBudget.Operation must exist.");
                java.lang.reflect.Field instance = admissionClazz.getDeclaredField("INSTANCE");
                Object admission = instance.get(null);
                java.lang.reflect.Method method = admissionClazz.getDeclaredMethod("admitOperation", Class.forName("net.minecraft.server.level.ServerPlayer"), operationClazz);
                context.assertNotNull(method, "AGCChunkAdmission admitOperation must be callable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R12_T1_4"; }
            @Override public String getName() { return "R12 Tier 1 - AGC Command Class"; }
            @Override public void run(TestContext context) throws Exception {
                context.checkClassExists("net.minecraft.server.commands.AGCCommand");
            }
        });
    }
}
