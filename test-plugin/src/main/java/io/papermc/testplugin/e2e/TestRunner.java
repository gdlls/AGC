package io.papermc.testplugin.e2e;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class TestRunner {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public static void runTests(JavaPlugin plugin) {
        if (!RUNNING.compareAndSet(false, true)) {
            plugin.getLogger().warning("E2E tests are already running!");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            File logFile = new File("logs/meteus-e2e.log");
            try {
                Files.createDirectories(logFile.getParentFile().toPath());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create logs directory", e);
            }

            try (PrintWriter logWriter = new PrintWriter(new FileWriter(logFile, false))) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
                logAndWrite(plugin, logWriter, "=================================================");
                logAndWrite(plugin, logWriter, "Starting AGC E2E Test Suite Execution at " + timestamp);
                logAndWrite(plugin, logWriter, "=================================================");

                List<E2ETest> tests = TestRegistry.getInstance().getTests();
                int total = tests.size();
                int passed = 0;
                int failed = 0;

                for (int i = 0; i < total; i++) {
                    E2ETest test = tests.get(i);
                    logAndWrite(plugin, logWriter, String.format("[%d/%d] Running %s (%s)...", (i + 1), total, test.getName(), test.getId()));
                    TestContext context = new TestContext(plugin);

                    long startTime = System.currentTimeMillis();
                    try {
                        test.run(context);
                        long duration = System.currentTimeMillis() - startTime;
                        logAndWrite(plugin, logWriter, String.format("  -> SUCCESS (%d ms)", duration));
                        passed++;
                    } catch (Throwable t) {
                        long duration = System.currentTimeMillis() - startTime;
                        logAndWrite(plugin, logWriter, String.format("  -> FAILED (%d ms): %s", duration, t.getMessage()));
                        t.printStackTrace(logWriter);
                        failed++;
                    }
                }

                logAndWrite(plugin, logWriter, "=================================================");
                logAndWrite(plugin, logWriter, "AGC E2E Test Suite Completed");
                logAndWrite(plugin, logWriter, String.format("Total: %d, Passed: %d, Failed: %d", total, passed, failed));
                logAndWrite(plugin, logWriter, "=================================================");

                logWriter.flush();

                if (failed == 0) {
                    plugin.getLogger().info("All tests passed! Shutting down server with code 0...");
                    System.exit(0);
                } else {
                    plugin.getLogger().severe("Some tests failed! Shutting down server with code 1...");
                    System.exit(1);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "An error occurred while running E2E tests", e);
                System.exit(1);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static void logAndWrite(JavaPlugin plugin, PrintWriter writer, String message) {
        plugin.getLogger().info(message);
        writer.println(message);
    }
}
