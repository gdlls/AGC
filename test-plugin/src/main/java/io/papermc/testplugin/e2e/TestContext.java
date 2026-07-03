package io.papermc.testplugin.e2e;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.CountDownLatch;

public class TestContext {
    private final JavaPlugin plugin;

    public TestContext(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) return;
        if (expected == null || !expected.equals(actual)) {
            throw new AssertionError(message + " (Expected: " + expected + ", Actual: " + actual + ")");
        }
    }

    public void assertNotNull(Object obj, String message) {
        if (obj == null) {
            throw new AssertionError(message);
        }
    }

    public void fail(String message) {
        throw new AssertionError(message);
    }

    public Class<?> checkClassExists(String className) throws ClassNotFoundException {
        return Class.forName(className);
    }

    public void delayTicks(int ticks) throws InterruptedException {
        if (ticks <= 0) return;
        CountDownLatch latch = new CountDownLatch(1);
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, latch::countDown, ticks);
        latch.await();
    }
}
