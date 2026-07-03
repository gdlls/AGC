package io.agcmc.agc.api.minigame;

import io.agcmc.agc.api.AGC;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Process-wide mini-game arena service. */
public final class AGCMinigames {
    private final Map<NamespacedKey, AGCArena> arenas = new LinkedHashMap<>();
    private final Map<UUID, NamespacedKey> playerArenaIndex = new LinkedHashMap<>();

    public synchronized @NotNull AGCArena createArena(final @NotNull NamespacedKey key, final @Nullable AGCArenaBounds bounds, final @NotNull AGCArenaOptions options) {
        AGC.scheduler().ensurePrimaryThread("minigames.createArena");
        final AGCArena arena = new AGCArena(key, bounds, options);
        final AGCArena previous = this.arenas.put(key, arena);
        if (previous != null) {
            this.removeIndexedPlayers(previous);
            previous.resetSilently();
        }
        return arena;
    }

    public synchronized @NotNull AGCArena createArena(final @NotNull NamespacedKey key, final @Nullable AGCArenaBounds bounds, final @NotNull AGCMinigameProfile profile) {
        return this.createArena(key, bounds, AGCArenaOptions.builder().profile(profile).build());
    }

    public synchronized boolean registerArena(final @NotNull AGCArena arena) {
        AGC.scheduler().ensurePrimaryThread("minigames.registerArena");
        return this.arenas.putIfAbsent(arena.key(), arena) == null;
    }

    public synchronized @NotNull Optional<AGCArena> arena(final @NotNull NamespacedKey key) {
        return Optional.ofNullable(this.arenas.get(key));
    }

    public synchronized @NotNull Optional<AGCArena> arenaOf(final @NotNull Player player) {
        final NamespacedKey key = this.playerArenaIndex.get(player.getUniqueId());
        return key == null ? Optional.empty() : Optional.ofNullable(this.arenas.get(key));
    }

    public synchronized @NotNull Collection<AGCArena> arenas() {
        return Collections.unmodifiableCollection(new ArrayList<>(this.arenas.values()));
    }

    public synchronized @NotNull Collection<AGCArena> arenasByPhase(final @NotNull AGCMatchPhase phase) {
        final ArrayList<AGCArena> matched = new ArrayList<>();
        for (final AGCArena arena : this.arenas.values()) {
            if (arena.phase() == phase) {
                matched.add(arena);
            }
        }
        return Collections.unmodifiableCollection(matched);
    }

    public @NotNull AGCMinigamePerformancePlan performancePlan(final @NotNull AGCArenaOptions options, final int expectedPlayers, final int expectedEntities) {
        final int players = Math.max(1, expectedPlayers);
        final int entities = Math.max(0, expectedEntities);
        final int radius = Math.max(0, options.preloadChunkRadius());
        final int shardSize = Math.max(1, options.playersPerShard());
        return new AGCMinigamePerformancePlan(
            players,
            entities,
            radius,
            shardSize,
            options.readOnlyPrewarm(),
            options.deterministicReset(),
            AGC.performance().denseArenaAdvice(players, entities, radius)
        );
    }

    public synchronized int arenaCount() {
        return this.arenas.size();
    }

    public synchronized int indexedPlayerCount() {
        return this.playerArenaIndex.size();
    }

    public @NotNull AGCArenaScalingPlan scalingPlan(final int expectedPlayers, final @NotNull AGCMinigameProfile profile) {
        final int playersPerShard = Math.max(8, profile.recommendedMaxPlayers());
        return new AGCArenaScalingPlan(
            profile,
            Math.max(0, expectedPlayers),
            playersPerShard,
            profile.recommendedPreloadRadius(),
            profile == AGCMinigameProfile.DUEL || profile == AGCMinigameProfile.BATTLE_ROYALE || profile == AGCMinigameProfile.INSTANCE_WORLD,
            true
        );
    }

    public synchronized @NotNull Collection<AGCArena> arenasForShard(final int shardId) {
        final int normalized = Math.max(0, shardId);
        final ArrayList<AGCArena> matched = new ArrayList<>();
        int index = 0;
        for (final AGCArena arena : this.arenas.values()) {
            final int perShard = Math.max(1, arena.options().playersPerShard());
            final int arenaShard = index / perShard;
            if (arenaShard == normalized) {
                matched.add(arena);
            }
            index++;
        }
        return Collections.unmodifiableCollection(matched);
    }

    public synchronized boolean unregisterArena(final @NotNull NamespacedKey key) {
        AGC.scheduler().ensurePrimaryThread("minigames.unregisterArena");
        final AGCArena arena = this.arenas.remove(key);
        if (arena == null) {
            return false;
        }
        this.removeIndexedPlayers(arena);
        arena.resetSilently();
        return true;
    }

    public synchronized boolean joinArena(final @NotNull Player player, final @NotNull NamespacedKey key) {
        AGC.scheduler().ensurePrimaryThread("minigames.joinArena");
        final AGCArena arena = this.arenas.get(key);
        if (arena == null) {
            return false;
        }
        final NamespacedKey previous = this.playerArenaIndex.get(player.getUniqueId());
        if (previous != null && !previous.equals(key)) {
            this.leaveArena(player);
        }
        final boolean joined = arena.join(player);
        if (joined) {
            this.playerArenaIndex.put(player.getUniqueId(), key);
        }
        return joined;
    }

    public synchronized boolean spectateArena(final @NotNull Player player, final @NotNull NamespacedKey key) {
        AGC.scheduler().ensurePrimaryThread("minigames.spectateArena");
        final AGCArena arena = this.arenas.get(key);
        final NamespacedKey previous = this.playerArenaIndex.get(player.getUniqueId());
        if (arena == null) {
            return false;
        }
        if (previous != null && !previous.equals(key)) {
            this.leaveArena(player);
        }
        if (!arena.spectate(player)) {
            return false;
        }
        this.playerArenaIndex.put(player.getUniqueId(), key);
        return true;
    }

    public synchronized boolean leaveArena(final @NotNull Player player) {
        AGC.scheduler().ensurePrimaryThread("minigames.leaveArena");
        final NamespacedKey key = this.playerArenaIndex.remove(player.getUniqueId());
        if (key == null) {
            return false;
        }
        final AGCArena arena = this.arenas.get(key);
        return arena != null && arena.leave(player);
    }

    public synchronized int removeOfflinePlayers() {
        int removed = 0;
        final java.util.Iterator<Map.Entry<UUID, NamespacedKey>> iterator = this.playerArenaIndex.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, NamespacedKey> entry = iterator.next();
            if (Bukkit.getPlayer(entry.getKey()) == null) {
                iterator.remove();
                removed++;
            }
        }
        for (final AGCArena arena : this.arenas.values()) {
            removed += arena.removeOfflinePlayers();
        }
        return removed;
    }

    public @NotNull BukkitTask countdown(
        final @NotNull Plugin plugin,
        final @NotNull AGCArena arena,
        final int seconds,
        final @NotNull Component prefix,
        final @NotNull Runnable onComplete
    ) {
        final boolean countdownStarted = arena.beginCountdown();
        return new BukkitRunnable() {
            private int remaining = Math.max(1, seconds);

            @Override
            public void run() {
                if (!countdownStarted || arena.phase() != AGCMatchPhase.COUNTDOWN) {
                    this.cancel();
                    return;
                }
                arena.broadcast(prefix.append(Component.text(" " + this.remaining)));
                this.remaining--;
                if (this.remaining <= 0) {
                    this.cancel();
                    onComplete.run();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void removeIndexedPlayers(final @NotNull AGCArena arena) {
        for (final UUID id : arena.playerIds()) {
            this.playerArenaIndex.remove(id);
        }
        for (final UUID id : arena.spectatorIds()) {
            this.playerArenaIndex.remove(id);
        }
    }
}
