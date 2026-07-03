package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR9Tests {
    public void register(TestRegistry registry) {
        // Tier 1 Tests
        registry.register(new E2ETest() {
            @Override public String getId() { return "R9_T1_1"; }
            @Override public String getName() { return "R9 Tier 1 - Debugger Class Load Verification"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                context.assertNotNull(clazz, "MeteusSpatialGrid compatibility alias should be loadable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R9_T1_2"; }
            @Override public String getName() { return "R9 Tier 1 - Debug Mode Status Field"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Field debugField = clazz.getDeclaredField("debugMode");
                context.assertNotNull(debugField, "debugMode field must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R9_T1_3"; }
            @Override public String getName() { return "R9 Tier 1 - Metrics Report Interval Getter"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method getReportInterval = clazz.getDeclaredMethod("getMetricsInterval");
                context.assertNotNull(getReportInterval, "getMetricsInterval method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R9_T1_4"; }
            @Override public String getName() { return "R9 Tier 1 - Dump Logs Output Path"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Field logPathField = clazz.getDeclaredField("metricsLogPath");
                context.assertNotNull(logPathField, "metricsLogPath field must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R9_T1_5"; }
            @Override public String getName() { return "R9 Tier 1 - Verbose Mode Flag Reader"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method isVerbose = clazz.getDeclaredMethod("isVerboseLogging");
                context.assertNotNull(isVerbose, "isVerboseLogging method must exist.");
            }
        });

        // Tier 2 Tests
        registry.register(new E2ETest() {
            @Override public String getId() { return "R9_T2_1"; }
            @Override public String getName() { return "R9 Tier 2 - Dump Metric Report JSON"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method dumpReport = clazz.getDeclaredMethod("generateMetricsReport");
                context.assertNotNull(dumpReport, "generateMetricsReport method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R9_T2_2"; }
            @Override public String getName() { return "R9 Tier 2 - Reset Accumulated Metrics"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method reset = clazz.getDeclaredMethod("resetMetrics");
                context.assertNotNull(reset, "resetMetrics method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R9_T2_3"; }
            @Override public String getName() { return "R9 Tier 2 - Toggle Verbose Logging State"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method toggle = clazz.getDeclaredMethod("setVerboseLogging", boolean.class);
                context.assertNotNull(toggle, "setVerboseLogging method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R9_T2_4"; }
            @Override public String getName() { return "R9 Tier 2 - Trigger Manual Spatial Grid Visualization"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method visualize = clazz.getDeclaredMethod("visualizeGridAround", org.bukkit.entity.Player.class);
                context.assertNotNull(visualize, "visualizeGridAround method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R9_T2_5"; }
            @Override public String getName() { return "R9 Tier 2 - Query Server TPS Alignment metrics"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method queryTps = clazz.getDeclaredMethod("getAverageLatencyMetrics");
                context.assertNotNull(queryTps, "getAverageLatencyMetrics method must exist.");
            }
        });
    }
}
