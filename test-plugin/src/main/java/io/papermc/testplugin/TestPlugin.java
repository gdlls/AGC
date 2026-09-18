package io.papermc.testplugin;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestRegistry;
import io.papermc.testplugin.e2e.TestRunner;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class TestPlugin extends JavaPlugin implements Listener {

    /**
     * Bench diagnostics: the NMS level name the parallel world tick conflict predicate compares.
     * Every non-primary level must report the same value for the predicate to serialize them.
     */
    private static String levelIdentity(final World world) {
        try {
            final Object handle = world.getClass().getMethod("getHandle").invoke(world);
            final Object levelData = handle.getClass().getField("serverLevelData").get(handle);
            final Object levelName = levelData.getClass().getMethod("getLevelName").invoke(levelData);
            return "levelName=" + levelName;
        } catch (final ReflectiveOperationException e) {
            return "levelName=<unavailable: " + e + ">";
        }
    }

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

        // AGC bench scaffolding: materialise N independently named flat worlds at runtime so the
        // multi-world paths (parallel world tick waves, hibernation, cross-world queues) can be
        // exercised against real ServerLevels instead of synthetic world handles. Created worlds are
        // ordinary Bukkit worlds; each gets its own folder/level name, which is exactly what the tick
        // loop's conflict predicate needs to be allowed to co-schedule them into one wave.
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(
                Commands.literal("agcbenchworlds")
                    .then(Commands.literal("create")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 512))
                            .executes(ctx -> {
                                final int count = IntegerArgumentType.getInteger(ctx, "count");
                                for (int i = 1; i <= count; i++) {
                                    final String name = "agcbench_" + i;
                                    if (this.getServer().getWorld(name) != null) {
                                        continue;
                                    }
                                    this.getServer().createWorld(new WorldCreator(name)
                                        .type(WorldType.FLAT)
                                        .generateStructures(false));
                                }
                                ctx.getSource().getSender().sendPlainMessage(
                                    "agcbenchworlds: total worlds now " + this.getServer().getWorlds().size());
                                return Command.SINGLE_SUCCESS;
                            })))
                    .then(Commands.literal("list")
                        .executes(ctx -> {
                            for (final World world : this.getServer().getWorlds()) {
                                // levelName is what MinecraftServer.tickChildren's wave-conflict predicate
                                // compares; print it so the multi-world bench can show whether two worlds
                                // are even allowed to share a parallel wave. Reflection on purpose: this
                                // plugin intentionally has no NMS compile dependency.
                                ctx.getSource().getSender().sendPlainMessage(
                                    "- " + world.getName() + " (" + world.getEnvironment() + ", players=" + world.getPlayers().size()
                                        + ", " + levelIdentity(world) + ")");
                            }
                            ctx.getSource().getSender().sendPlainMessage(
                                "agcbenchworlds: total " + this.getServer().getWorlds().size());
                            return Command.SINGLE_SUCCESS;
                        }))
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
