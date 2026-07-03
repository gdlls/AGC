package io.agcmc.agc.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.Consumer;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/** Compatibility-first scheduler helpers for plugins using AGC APIs. */
public final class AGCScheduler {
    AGCScheduler() {
    }

    public void ensurePrimaryThread(final @NotNull String operation) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("AGC operation '" + operation + "' must run on the primary server thread for Bukkit/Paper compatibility");
        }
    }

    public void runPrimary(final @NotNull Plugin plugin, final @NotNull Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public @NotNull BukkitTask runLater(final @NotNull Plugin plugin, final @NotNull Runnable task, final long delayTicks) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, delayTicks));
    }

    public @NotNull BukkitTask runTimer(final @NotNull Plugin plugin, final @NotNull Runnable task, final long delayTicks, final long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, Math.max(0L, delayTicks), Math.max(1L, periodTicks));
    }


    /**
     * Runs a plugin-supplied read-only preparation task asynchronously and then
     * commits on the primary thread. The prepare task must not call mutating
     * Bukkit APIs; the commit task is where Bukkit-visible state changes belong.
     */
    public @NotNull BukkitTask prepareAsyncThenCommit(
        final @NotNull Plugin plugin,
        final @NotNull Runnable readOnlyPrepare,
        final @NotNull Runnable orderedCommit
    ) {
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            readOnlyPrepare.run();
            Bukkit.getScheduler().runTask(plugin, orderedCommit);
        });
    }

    /**
     * Runs read-only work asynchronously and exposes a future. Mutating Bukkit
     * operations must be scheduled through runPrimary/prepareAsyncThenCommit.
     */
    public @NotNull CompletableFuture<Void> readOnlyAsync(final @NotNull Plugin plugin, final @NotNull Runnable readOnlyPrepare) {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                readOnlyPrepare.run();
                future.complete(null);
            } catch (final Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    /**
     * Runs read-only work asynchronously and commits the prepared value on the
     * primary thread. This is the recommended AGC pattern for expensive
     * mini-game planning: compute off-thread, mutate Bukkit-visible state only
     * in the ordered commit callback.
     */
    public <T> @NotNull BukkitTask prepareSupplyAsyncThenCommit(
        final @NotNull Plugin plugin,
        final @NotNull Supplier<T> readOnlyPrepare,
        final @NotNull Consumer<T> orderedCommit
    ) {
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final T prepared = readOnlyPrepare.get();
            Bukkit.getScheduler().runTask(plugin, () -> orderedCommit.accept(prepared));
        });
    }

    /** Returns a future for read-only supplied data; callers must still commit via runPrimary. */
    public <T> @NotNull CompletableFuture<T> readOnlySupplyAsync(final @NotNull Plugin plugin, final @NotNull Supplier<T> readOnlyPrepare) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                future.complete(readOnlyPrepare.get());
            } catch (final Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    /**
     * Region-hinted scheduling surface. On classic Paper this intentionally
     * falls back to the primary thread; future region-threaded runtimes may
     * route this to a region executor without changing plugin code.
     */
    public void runRegion(final @NotNull Plugin plugin, final @NotNull Location regionHint, final @NotNull Runnable task) {
        this.runPrimary(plugin, task);
    }
}
