package io.agcmc.agc.api.minigame;

import io.agcmc.agc.api.AGC;
import io.agcmc.agc.api.event.AGCArenaPrepareEvent;
import io.agcmc.agc.api.event.AGCArenaResetEvent;
import io.agcmc.agc.api.event.AGCMatchEndEvent;
import io.agcmc.agc.api.event.AGCMatchStartEvent;
import io.agcmc.agc.api.event.AGCPlayerJoinArenaEvent;
import io.agcmc.agc.api.event.AGCPlayerLeaveArenaEvent;
import io.agcmc.agc.api.event.AGCPlayerSpectateArenaEvent;
import io.agcmc.agc.api.event.AGCTeamAssignEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lightweight arena implementation intended for mini-game plugins.
 * All mutating operations are primary-thread only to preserve Bukkit semantics.
 */
public final class AGCArena {
    private final NamespacedKey key;
    private final AGCArenaOptions options;
    private final AGCArenaBounds bounds;
    private final LinkedHashSet<UUID> players = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> spectators = new LinkedHashSet<>();
    private final LinkedHashMap<String, AGCTeam> teams = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, String> playerTeams = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, AGCPlayerSnapshot> snapshots = new LinkedHashMap<>();
    private AGCMatchPhase phase = AGCMatchPhase.LOBBY;
    private Instant matchStartedAt;

    public AGCArena(final @NotNull NamespacedKey key, final @Nullable AGCArenaBounds bounds, final @NotNull AGCArenaOptions options) {
        this.key = key;
        this.bounds = bounds;
        this.options = options;
    }

    public @NotNull NamespacedKey key() { return this.key; }
    public @NotNull AGCArenaOptions options() { return this.options; }
    public @Nullable AGCArenaBounds bounds() { return this.bounds; }
    public synchronized @NotNull AGCMatchPhase phase() { return this.phase; }

    public synchronized boolean prepare() {
        AGC.scheduler().ensurePrimaryThread("arena.prepare");
        if (this.phase != AGCMatchPhase.LOBBY) {
            return false;
        }
        final AGCArenaPrepareEvent event = new AGCArenaPrepareEvent(this);
        return event.callEvent();
    }

    public synchronized boolean canStart() {
        return this.phase != AGCMatchPhase.RUNNING && this.players.size() >= this.options.minPlayers();
    }

    public synchronized boolean join(final @NotNull Player player) {
        AGC.scheduler().ensurePrimaryThread("arena.join");
        if (this.players.contains(player.getUniqueId())) {
            return true;
        }
        if (this.players.size() >= this.options.maxPlayers()) {
            return false;
        }
        final AGCPlayerJoinArenaEvent event = new AGCPlayerJoinArenaEvent(this, player);
        if (!event.callEvent()) {
            return false;
        }
        if (this.options.snapshotPlayers()) {
            this.snapshots.put(player.getUniqueId(), AGCPlayerSnapshot.capture(player));
        }
        if (this.options.clearInventoryOnJoin()) {
            player.getInventory().clear();
            player.updateInventory();
        }
        this.spectators.remove(player.getUniqueId());
        return this.players.add(player.getUniqueId());
    }

    public synchronized boolean spectate(final @NotNull Player player) {
        AGC.scheduler().ensurePrimaryThread("arena.spectate");
        if (!this.options.allowSpectators()) {
            return false;
        }
        final AGCPlayerSpectateArenaEvent event = new AGCPlayerSpectateArenaEvent(this, player);
        if (!event.callEvent()) {
            return false;
        }
        if (this.options.snapshotPlayers()) {
            this.snapshots.putIfAbsent(player.getUniqueId(), AGCPlayerSnapshot.capture(player));
        }
        this.players.remove(player.getUniqueId());
        final String teamId = this.playerTeams.remove(player.getUniqueId());
        if (teamId != null) {
            final AGCTeam team = this.teams.get(teamId);
            if (team != null) {
                team.remove(player);
            }
        }
        return this.spectators.add(player.getUniqueId());
    }

    public synchronized boolean leave(final @NotNull Player player) {
        AGC.scheduler().ensurePrimaryThread("arena.leave");
        final boolean removed = this.players.remove(player.getUniqueId()) | this.spectators.remove(player.getUniqueId());
        if (!removed) {
            return false;
        }
        final String teamId = this.playerTeams.remove(player.getUniqueId());
        if (teamId != null) {
            final AGCTeam team = this.teams.get(teamId);
            if (team != null) {
                team.remove(player);
            }
        }
        new AGCPlayerLeaveArenaEvent(this, player).callEvent();
        if (this.options.restorePlayersOnLeave()) {
            final AGCPlayerSnapshot snapshot = this.snapshots.remove(player.getUniqueId());
            if (snapshot != null) {
                snapshot.restore(player, this.options.restoreLocationOnLeave());
            }
        }
        if (this.options.resetOnEmpty() && this.players.isEmpty() && this.spectators.isEmpty() && this.phase != AGCMatchPhase.LOBBY) {
            this.reset("empty-arena");
        }
        return true;
    }

    public synchronized @NotNull AGCTeam team(final @NotNull String id, final @NotNull Component displayName) {
        return this.teams.computeIfAbsent(id, ignored -> new AGCTeam(id, displayName));
    }

    public synchronized boolean assignTeam(final @NotNull Player player, final @NotNull String teamId) {
        AGC.scheduler().ensurePrimaryThread("arena.assignTeam");
        final AGCTeam team = this.teams.get(teamId);
        if (team == null || !this.players.contains(player.getUniqueId())) {
            return false;
        }
        final AGCTeamAssignEvent event = new AGCTeamAssignEvent(this, player, teamId);
        if (!event.callEvent()) {
            return false;
        }
        final String oldTeam = this.playerTeams.put(player.getUniqueId(), teamId);
        if (oldTeam != null) {
            final AGCTeam old = this.teams.get(oldTeam);
            if (old != null) {
                old.remove(player);
            }
        }
        return team.add(player);
    }

    public synchronized boolean beginCountdown() {
        AGC.scheduler().ensurePrimaryThread("arena.beginCountdown");
        if (this.phase != AGCMatchPhase.LOBBY || this.players.size() < this.options.minPlayers()) {
            return false;
        }
        this.phase = AGCMatchPhase.COUNTDOWN;
        return true;
    }

    public synchronized boolean start() {
        AGC.scheduler().ensurePrimaryThread("arena.start");
        if (!this.canStart()) {
            return false;
        }
        final AGCMatchStartEvent event = new AGCMatchStartEvent(this);
        if (!event.callEvent()) {
            return false;
        }
        this.phase = AGCMatchPhase.RUNNING;
        this.matchStartedAt = Instant.now();
        return true;
    }

    public synchronized @NotNull AGCMatchResult end(final @NotNull String reason, final @Nullable String winningTeamId) {
        AGC.scheduler().ensurePrimaryThread("arena.end");
        final Duration duration = this.matchStartedAt == null ? Duration.ZERO : Duration.between(this.matchStartedAt, Instant.now());
        this.phase = AGCMatchPhase.ENDING;
        final AGCMatchResult result = new AGCMatchResult(reason, winningTeamId, duration);
        new AGCMatchEndEvent(this, result).callEvent();
        this.phase = AGCMatchPhase.LOBBY;
        this.matchStartedAt = null;
        return result;
    }

    public synchronized void reset(final @NotNull String reason) {
        AGC.scheduler().ensurePrimaryThread("arena.reset");
        this.phase = AGCMatchPhase.LOBBY;
        this.matchStartedAt = null;
        this.playerTeams.clear();
        for (final AGCTeam team : this.teams.values()) {
            team.clear();
        }
        new AGCArenaResetEvent(this, reason).callEvent();
    }

    public synchronized void broadcast(final @NotNull Component message) {
        AGC.scheduler().ensurePrimaryThread("arena.broadcast");
        for (final UUID id : this.players) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendMessage(message);
            }
        }
        for (final UUID id : this.spectators) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    public synchronized int teleportPlayers(final @NotNull Location location) {
        AGC.scheduler().ensurePrimaryThread("arena.teleportPlayers");
        int moved = 0;
        for (final UUID id : this.players) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null && player.teleport(location)) {
                moved++;
            }
        }
        return moved;
    }

    public synchronized void applyLoadout(final @NotNull AGCLoadout loadout) {
        AGC.scheduler().ensurePrimaryThread("arena.applyLoadout");
        for (final UUID id : this.players) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                loadout.apply(player);
            }
        }
    }

    public synchronized int onlineParticipantCount() {
        int count = 0;
        for (final UUID id : this.players) {
            if (Bukkit.getPlayer(id) != null) {
                count++;
            }
        }
        for (final UUID id : this.spectators) {
            if (Bukkit.getPlayer(id) != null) {
                count++;
            }
        }
        return count;
    }

    public synchronized @NotNull Collection<Player> onlinePlayers() {
        final ArrayList<Player> online = new ArrayList<>(this.players.size());
        for (final UUID id : this.players) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                online.add(player);
            }
        }
        return Collections.unmodifiableList(online);
    }

    public synchronized void forEachOnlineParticipant(final @NotNull Consumer<Player> consumer) {
        for (final UUID id : this.players) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                consumer.accept(player);
            }
        }
        for (final UUID id : this.spectators) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                consumer.accept(player);
            }
        }
    }

    public synchronized int removeOfflinePlayers() {
        int removed = 0;
        final java.util.Iterator<UUID> playerIterator = this.players.iterator();
        while (playerIterator.hasNext()) {
            final UUID id = playerIterator.next();
            if (Bukkit.getPlayer(id) == null) {
                playerIterator.remove();
                this.playerTeams.remove(id);
                this.snapshots.remove(id);
                removed++;
            }
        }
        final java.util.Iterator<UUID> spectatorIterator = this.spectators.iterator();
        while (spectatorIterator.hasNext()) {
            final UUID id = spectatorIterator.next();
            if (Bukkit.getPlayer(id) == null) {
                spectatorIterator.remove();
                this.snapshots.remove(id);
                removed++;
            }
        }
        return removed;
    }

    public synchronized boolean contains(final @NotNull Player player) {
        return this.players.contains(player.getUniqueId()) || this.spectators.contains(player.getUniqueId());
    }

    public synchronized @NotNull Optional<String> teamOf(final @NotNull Player player) {
        return Optional.ofNullable(this.playerTeams.get(player.getUniqueId()));
    }

    public synchronized @NotNull Set<UUID> playerIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.players));
    }

    public synchronized @NotNull Set<UUID> spectatorIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.spectators));
    }

    public synchronized @NotNull Collection<AGCTeam> teams() {
        return Collections.unmodifiableCollection(new ArrayList<>(this.teams.values()));
    }

    public synchronized @NotNull Map<UUID, String> playerTeams() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(this.playerTeams));
    }

    synchronized void resetSilently() {
        this.players.clear();
        this.spectators.clear();
        this.playerTeams.clear();
        this.snapshots.clear();
        this.phase = AGCMatchPhase.LOBBY;
        this.matchStartedAt = null;
    }
}
