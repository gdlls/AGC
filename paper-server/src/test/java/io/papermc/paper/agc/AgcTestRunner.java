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
        AgcConfigSyncTest.class,
        AgcHotPathCacheTest.class,
        AgcFoliaTuningTest.class,
        AgcNetworkEnhancerTest.class,
        AgcMetricsTest.class,
        AgcCrossWorldQueueTest.class,
        AgcParallelWorldTickEngineTest.class,
        AgcParallelWorldTickOverlapPreconditionsTest.class,
        io.papermc.paper.agc.spawner.AgcUnmappedSpawnTypeGuardTest.class,
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
        AgcTickThreadFastPathTest.class,
        AgcClassWarmupTest.class,
        AgcPluginSafetyGuardTest.class,
        AgcPluginScannerTest.class,
        AgcMetricsExporterTest.class,
        AgcDashboardRendererTest.class,
        AgcPaletteCowOptimizerTest.class,
        AgcStorageIoGovernorTest.class,
        AgcBehaviorParitySuiteTest.class,
        AgcPluginVirtualizerTest.class,
        AgcOptimisticTransactionManagerTest.class,
        AgcBytecodeInstrumentationBridgeTest.class,
        AgcPluginCompatibilityVerifierTest.class,
        AgcDynamicRegionClusteringEngineTest.class,
        AgcLockFreeRcuChunkMapTest.class,
        AgcNumaWorkStealingSchedulerTest.class,
        AgcSoaEntityPhysicsEngineTest.class,
        AgcSimdCollisionKernelTest.class,
        AgcFastRedstoneSimdEngineTest.class,
        AgcNativeIoUringNetworkEngineTest.class,
        AgcZeroCopyBroadcastHubTest.class,
        AgcBitLevelDeltaEntityTrackerTest.class,
        AgcPanamaOffHeapChunkStorageTest.class,
        AgcNativeJpsPathfinderTest.class,
        AgcHierarchicalActivationRangeV3Test.class,
        AgcDirectIoChunkStorageEngineTest.class,
        Agc3TierWorldLifecycleCoordinatorTest.class,
        AgcAutonomousPidGovernorTest.class,
        AgcChaosFaultInjectorTest.class,
        AgcUltraScaleStressBenchmarkTest.class,
        AgcMassiveStressBenchmarkTest.class,
        AgcHardwareTopologyDetectorTest.class,
        AgcSingleplayerFeelCombatEngineTest.class,
        AgcHotPathRuntimeBridgeTest.class,
        AgcScalePresetManagerTest.class,
        AgcGcTuningAdvisorTest.class,
        AgcAdaptiveGovernorTest.class,
        io.papermc.paper.agc.profiling.AgcTickProfilerTest.class,
        io.papermc.paper.agc.profiling.AgcMemoryTrackerTest.class,
        io.papermc.paper.agc.profiling.AgcEntityCounterTest.class,
        io.papermc.paper.agc.tick.AgcTickBudgetAllocatorTest.class,
        io.papermc.paper.agc.entity.AgcEntityTickSchedulerTest.class,
        io.papermc.paper.agc.chunk.AgcChunkGenThrottlerTest.class,
        io.papermc.paper.agc.chunk.AgcChunkCacheHierarchyTest.class,
        io.papermc.paper.agc.chunk.AgcEmptyChunkOptimizerTest.class,
        io.papermc.paper.agc.io.AgcRegionFileDirectIoEngineTest.class,
        io.papermc.paper.agc.network.AgcBroadcastOptimizerTest.class,
        io.papermc.paper.agc.network.AgcConnectionManagerTest.class,
        io.papermc.paper.agc.network.AgcChunkPacketOptimizerTest.class,
        io.papermc.paper.agc.world.AgcWorldMemoryManagerTest.class,
        io.papermc.paper.agc.world.AgcDynamicViewDistanceEngineTest.class,
        io.papermc.paper.agc.memory.AgcVec3PoolTest.class,
        io.papermc.paper.agc.memory.AgcOffHeapStorageTest.class,
        io.papermc.paper.agc.jvm.AgcJvmTunerTest.class,
        io.papermc.paper.agc.tick.AgcBlockTickBatcherTest.class,
        io.papermc.paper.agc.redstone.AgcRedstoneOptimizerTest.class,
        io.papermc.paper.agc.hopper.AgcHopperOptimizerTest.class,
        io.papermc.paper.agc.event.AgcLockFreeEventDispatcherTest.class,
        io.papermc.paper.agc.event.AgcAsyncEventPipelineTest.class,
        io.papermc.paper.agc.plugin.AgcPluginWatchdogTest.class,
        io.papermc.paper.agc.metrics.AgcPrometheusExporterTest.class,
        io.papermc.paper.agc.selfhealing.AgcSelfHealingEngineTest.class,
        io.papermc.paper.agc.ds.AgcTimingWheelTest.class,
        io.papermc.paper.agc.ds.AgcSpatialGridTest.class,
        io.papermc.paper.agc.ds.AgcFastHasherTest.class,
        io.papermc.paper.agc.jit.AgcTypeDispatcherTest.class,
        io.papermc.paper.agc.simd.AgcVectorMathTest.class,
        io.papermc.paper.agc.nativex.AgcNativeAcceleratorTest.class,
        io.papermc.paper.agc.villager.AgcVillagerOptimizerTest.class,
        io.papermc.paper.agc.spawner.AgcSpawnerOptimizerTest.class,
        io.papermc.paper.agc.explosion.AgcExplosionOptimizerTest.class,
        io.papermc.paper.agc.io.AgcAsyncSavePipelineTest.class,
        io.papermc.paper.agc.io.AgcPlayerDataOptimizerTest.class,
        io.papermc.paper.agc.io.AgcRegionFileManagerTest.class,
        io.papermc.paper.agc.command.AgcCommandOptimizerTest.class,
        io.papermc.paper.agc.scoreboard.AgcScoreboardOptimizerTest.class,
        io.papermc.paper.agc.chat.AgcChatOptimizerTest.class,
        io.papermc.paper.agc.worldgen.AgcNoiseOptimizerTest.class,
        io.papermc.paper.agc.worldgen.AgcJigsawBoxOctreeTest.class,
        io.papermc.paper.agc.worldgen.AgcTemplatePoolDedupTest.class,
        io.papermc.paper.agc.worldgen.AgcFastNoiseEngineTest.class,
        io.papermc.paper.agc.worldgen.AgcStructureNbtPrunerTest.class,
        io.papermc.paper.agc.entity.AgcLithiumCollisionEngineTest.class,
        io.papermc.paper.agc.entity.AgcPoiSearchEngineTest.class,
        io.papermc.paper.agc.light.AgcParallelLightEngineTest.class,
        io.papermc.paper.agc.chunk.AgcC2meChunkPipelineTest.class,
        io.papermc.paper.agc.chunk.AgcChunkSendCacheTest.class,
        io.papermc.paper.agc.chunk.AgcTeleportAndPaletteCompactTest.class,
        io.papermc.paper.agc.network.AgcUniverseNetEngineTest.class,
        io.papermc.paper.agc.tick.AgcRegionTickBridgeTest.class,
        io.papermc.paper.agc.worldgen.AgcStructureOptimizerTest.class,
        io.papermc.paper.agc.worldgen.AgcFeatureOptimizerTest.class,
        io.papermc.paper.agc.light.AgcStarLightBatchOptimizerTest.class,
        io.papermc.paper.agc.network.AgcFastNetworkSerializationEngineTest.class,
        io.papermc.paper.agc.memory.AgcOffHeapSlabAllocatorTest.class,
        io.papermc.paper.agc.AgcHotPathRuntimeWireVerificationTest.class,
        io.papermc.paper.agc.AgcFullEndToEndIntegrationTest.class,
        AgcNettyIoThreadTest.class,
        AgcSaveCoalescerTest.class,
        AgcStaticPacketEncodingCacheTest.class,
        AgcFeatureWiringAuditTest.class,
        AgcFeatureScoperTest.class,
        net.minecraft.world.level.levelgen.synth.ImprovedNoiseGradientParityTest.class,
        net.minecraft.advancements.AdvancementLazyProgressParityTest.class,
        io.papermc.paper.agc.network.AgcBroadcastPacketParityTest.class,
        io.papermc.paper.agc.AgcAdvancedOptimizationsSuiteTest.class
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
        final String filter = args.length > 0 && !args[0].isBlank() ? args[0] : System.getProperty("agc.test.filter");

        for (final Class<?> testClass : TEST_CLASSES) {
            if (filter != null && !filter.isBlank() && !testClass.getSimpleName().contains(filter)) {
                continue;
            }
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
