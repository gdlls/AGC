package net.minecraft.server;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * AGC latency-compensation ring buffer.
 * <p>
 * The class records lightweight location snapshots and exposes safe lookup
 * helpers. It does not alter combat by default; integrations should only use it
 * when the opt-in AGC/Paper config enables hit rewind.
 */
public class MeteusHitRewind {
    public volatile boolean enabled = false;
    public volatile Object packetListener = null;
    public volatile double pingMultiplier = 0.5D;
    public volatile int maxCompensationTicks = 10;

    private static final long TICK_MS = 50L;
    private final int maxHistory = 64;
    private final Map<UUID, ArrayDeque<State>> history = new ConcurrentHashMap<>();
    private final Map<UUID, Location> restoreLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> packetSequences = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<Integer>> pingSamples = new ConcurrentHashMap<>();

    public int getMaxLatencyMs() {
        return (int) (this.maxCompensationTicks * TICK_MS);
    }

    public int getHistorySize(final Entity entity) {
        if (entity == null) {
            return 0;
        }
        final ArrayDeque<State> states = this.history.get(entity.getUniqueId());
        return states == null ? 0 : states.size();
    }

    public int getTickWindow(final int latencyMs) {
        return Math.max(0, Math.min(this.maxCompensationTicks, convertMsToTicks(Math.max(0L, latencyMs))));
    }

    public void recordState(final Entity entity, final long tick) {
        if (entity == null) {
            return;
        }
        final ArrayDeque<State> states = this.history.computeIfAbsent(entity.getUniqueId(), ignored -> new ArrayDeque<>());
        synchronized (states) {
            states.addLast(new State(tick, entity.getLocation().clone()));
            while (states.size() > this.maxHistory) {
                states.removeFirst();
            }
        }
    }

    public Location getHistoricalLocation(final Entity entity, final long tick) {
        if (entity == null) {
            return null;
        }
        final ArrayDeque<State> states = this.history.get(entity.getUniqueId());
        if (states == null || states.isEmpty()) {
            return entity.getLocation();
        }
        synchronized (states) {
            State best = null;
            long bestDistance = Long.MAX_VALUE;
            for (final State state : states) {
                final long distance = Math.abs(state.tick - tick);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = state;
                }
            }
            return best == null ? entity.getLocation() : best.location.clone();
        }
    }

    public boolean rewindEntityTo(final Entity entity, final long tick) {
        if (entity == null) {
            return false;
        }
        final Location historical = this.getHistoricalLocation(entity, tick);
        if (historical == null) {
            return false;
        }
        this.restoreLocations.putIfAbsent(entity.getUniqueId(), entity.getLocation().clone());
        return entity.teleport(historical);
    }

    public boolean restoreEntityState(final Entity entity) {
        if (entity == null) {
            return false;
        }
        final Location original = this.restoreLocations.remove(entity.getUniqueId());
        return original != null && entity.teleport(original);
    }

    public void pruneOldStates(final long minimumTick) {
        for (final ArrayDeque<State> states : this.history.values()) {
            synchronized (states) {
                while (!states.isEmpty() && states.peekFirst().tick < minimumTick) {
                    states.removeFirst();
                }
            }
        }
    }

    public int getPingMs(final Player player) {
        return player == null ? 0 : Math.max(0, player.getPing());
    }

    public int getJitterMs(final Player player) {
        if (player == null) {
            return 0;
        }
        final int ping = this.getPingMs(player);
        final ArrayDeque<Integer> samples = this.pingSamples.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        synchronized (samples) {
            samples.addLast(ping);
            while (samples.size() > 8) {
                samples.removeFirst();
            }
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (final int sample : samples) {
                min = Math.min(min, sample);
                max = Math.max(max, sample);
            }
            return samples.isEmpty() ? 0 : max - min;
        }
    }

    public int calculateLatencyOffset(final Player player) {
        return this.getTickWindow((int) Math.round(this.getPingMs(player) * this.pingMultiplier));
    }

    public void injectPlayerChannel(final Player player) {
        if (player != null) {
            this.packetSequences.putIfAbsent(player.getUniqueId(), -1);
        }
    }

    public void ejectPlayerChannel(final Player player) {
        if (player != null) {
            this.packetSequences.remove(player.getUniqueId());
            this.pingSamples.remove(player.getUniqueId());
        }
    }

    public void onPacketInbound(final Player player, final Object packet) {
        if (player != null) {
            this.getJitterMs(player);
        }
    }

    public boolean verifyPacketSequence(final Player player, final int sequence) {
        if (player == null) {
            return false;
        }
        final UUID uuid = player.getUniqueId();
        final Integer previous = this.packetSequences.put(uuid, sequence);
        return previous == null || sequence >= previous;
    }

    public int convertMsToTicks(final long milliseconds) {
        if (milliseconds <= 0L) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (milliseconds + TICK_MS - 1L) / TICK_MS);
    }

    public int calculateTickDelta(final Player player) {
        return this.calculateLatencyOffset(player);
    }

    public int getTickOffset(final Player player) {
        return this.calculateLatencyOffset(player);
    }

    public int adjustTickAlignment(final Player player, final int serverTick) {
        return Math.max(0, serverTick - this.calculateLatencyOffset(player));
    }

    public boolean isTrackingClientTicks(final Player player) {
        return player != null && this.packetSequences.containsKey(player.getUniqueId());
    }

    public void logOutOfSyncEvent(final Player player, final int delta) {
        if (player != null && delta > this.maxCompensationTicks) {
            player.sendMessage("AGC latency compensation skipped: client/server tick delta " + delta + " exceeds cap " + this.maxCompensationTicks);
        }
    }

    public void resyncClock(final Player player) {
        if (player != null) {
            this.packetSequences.put(player.getUniqueId(), -1);
        }
    }

    public Location getCompensatedLocation(final Entity entity, final Player attacker) {
        if (entity == null) {
            return null;
        }
        final int offset = this.calculateLatencyOffset(attacker);
        final long estimatedCurrentTick = System.currentTimeMillis() / TICK_MS;
        return this.getHistoricalLocation(entity, estimatedCurrentTick - offset);
    }

    private record State(long tick, Location location) {}
}
