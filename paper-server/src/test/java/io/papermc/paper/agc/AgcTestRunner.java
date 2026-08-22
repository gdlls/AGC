package io.papermc.paper.agc;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone test runner for AGC unit test suites.
 * Executes all @Test methods across AGC test classes and reports detailed results.
 */
public final class AgcTestRunner {

    private static final Class<?>[] TEST_CLASSES = new Class<?>[] {
        AgcPerformanceTuningTest.class,
        AgcCapabilityMatrixTest.class,
        AgcHotPathCacheTest.class,
        AgcFoliaTuningTest.class,
        AgcNetworkEnhancerTest.class,
        AgcMetricsTest.class,
        AgcCommandTest.class,
        AgcCrossWorldQueueTest.class,
        AgcParallelWorldTickEngineTest.class,
        AgcPacketBroadcastDeduplicatorTest.class,
        AgcFlushCoalescerTest.class,
        AgcPacketPrioritySchedulerTest.class,
        AgcHierarchicalActivationRangeTest.class,
        AgcEntityAiBatchProcessorTest.class,
        AgcSpatialEntityIndexTest.class,
        AgcWorldHibernationEngineTest.class,
        AgcAdaptiveViewDistanceControllerTest.class,
        AgcChunkFairLoadArbiterTest.class,
        AgcHotObjectPoolTest.class,
        AgcPrimitiveCollectionsTest.class,
        AgcDirectBufferPoolTest.class,
        AgcStabilityJournalTest.class,
        AgcPerformanceGovernorTest.class,
        AgcPluginSafetyGuardTest.class,
        AgcPluginScannerTest.class,
        AgcMetricsExporterTest.class,
        AgcDashboardRendererTest.class,
        AgcPaletteCowOptimizerTest.class,
        AgcStorageIoGovernorTest.class,
        AgcBehaviorParitySuiteTest.class,
        AgcMassiveStressBenchmarkTest.class
    };

    public static void main(final String[] args) {
        System.out.println("=================================================");
        System.out.println("   AGC Performance Layer Unit Test Suite");
        System.out.println("=================================================");

        int totalTests = 0;
        int passedTests = 0;
        int failedTests = 0;
        final List<String> failures = new ArrayList<>();

        final long startTotal = System.nanoTime();

        for (final Class<?> testClass : TEST_CLASSES) {
            System.out.println("\n[RUNNING] " + testClass.getSimpleName());
            final Method[] methods = testClass.getDeclaredMethods();

            Method beforeEach = null;
            Method afterEach = null;

            for (final Method m : methods) {
                if (m.isAnnotationPresent(org.junit.jupiter.api.BeforeEach.class)) {
                    beforeEach = m;
                    m.setAccessible(true);
                }
                if (m.isAnnotationPresent(org.junit.jupiter.api.AfterEach.class)) {
                    afterEach = m;
                    m.setAccessible(true);
                }
            }

            for (final Method method : methods) {
                if (method.isAnnotationPresent(org.junit.jupiter.api.Test.class)) {
                    totalTests++;
                    final String testName = testClass.getSimpleName() + "#" + method.getName();
                    method.setAccessible(true);

                    try {
                        final Object instance = testClass.getDeclaredConstructor().newInstance();
                        if (beforeEach != null) {
                            beforeEach.invoke(instance);
                        }

                        final long start = System.nanoTime();
                        method.invoke(instance);
                        final long durationMs = (System.nanoTime() - start) / 1_000_000;

                        if (afterEach != null) {
                            afterEach.invoke(instance);
                        }

                        passedTests++;
                        System.out.println("  + PASSED: " + method.getName() + " (" + durationMs + "ms)");
                    } catch (final Throwable t) {
                        failedTests++;
                        final Throwable cause = t.getCause() != null ? t.getCause() : t;
                        final String failMsg = testName + " FAILED: " + cause.getMessage();
                        failures.add(failMsg);
                        System.err.println("  X FAILED: " + method.getName() + " -> " + cause);
                        cause.printStackTrace(System.err);
                    }
                }
            }
        }

        final long totalMs = (System.nanoTime() - startTotal) / 1_000_000;
        System.out.println("\n=================================================");
        System.out.println(String.format("   RESULTS: Total: %d, Passed: %d, Failed: %d (in %dms)",
            totalTests, passedTests, failedTests, totalMs));
        System.out.println("=================================================");

        if (failedTests > 0) {
            System.err.println("\nFailure summary (" + failedTests + "):");
            for (final String f : failures) {
                System.err.println("  - " + f);
            }
            System.exit(1);
        } else {
            System.out.println("\nALL AGC UNIT TESTS PASSED SUCCESSFULLY!");
            System.exit(0);
        }
    }
}
