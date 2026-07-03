package io.papermc.testplugin;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestRegistry;
import io.papermc.testplugin.e2e.TestRunner;
import com.mojang.brigadier.Command;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class TestPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);

        // Register Brigadier commands
        final LifecycleEventManager<Plugin> lifecycleManager = this.getLifecycleManager();
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(
                Commands.literal("agctest")
                    .then(Commands.literal("run")
                        .executes(ctx -> {
                            ctx.getSource().getSender().sendPlainMessage("Starting AGC E2E tests asynchronously...");
                            TestRunner.runTests(this);
                            return Command.SINGLE_SUCCESS;
                        })
                    )
                    .then(Commands.literal("list")
                        .executes(ctx -> {
                            ctx.getSource().getSender().sendPlainMessage("Registered AGC E2E tests:");
                            for (E2ETest test : TestRegistry.getInstance().getTests()) {
                                ctx.getSource().getSender().sendPlainMessage("- " + test.getId() + ": " + test.getName());
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                    )
                    .build()
            );
        });

        // Auto-run trigger
        if (Boolean.getBoolean("agc.e2e.auto-run") || Boolean.getBoolean("meteus.e2e.auto-run")) {
            getLogger().info("agc.e2e.auto-run system property is true. Scheduling E2E test runner in 5 seconds...");
            getServer().getScheduler().runTaskLater(this, () -> {
                getLogger().info("Executing E2E tests automatically...");
                TestRunner.runTests(this);
            }, 100L); // 100 ticks = 5 seconds
        }
    }
}
