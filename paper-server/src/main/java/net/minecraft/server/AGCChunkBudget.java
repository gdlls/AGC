package net.minecraft.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;

/**
 * Bounded chunk operation budget used by AGC scaling integrations.
 * <p>
 * It provides fair per-player send/load/generate admission without altering the
 * chunk data itself. Actual queue integration remains opt-in so default Paper
 * chunk loading semantics are preserved.
 */
public final class AGCChunkBudget {
    public static final AGCChunkBudget INSTANCE = new AGCChunkBudget();

    public enum Operation {
        SEND,
        LOAD,
        GENERATE
    }

    private final Map<UUID, PlayerBudget> playerBudgets = new ConcurrentHashMap<>();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong throttled = new AtomicLong();
    private volatile int sendsPerSecond = 96;
    private volatile int loadsPerSecond = 80;
    private volatile int generatesPerSecond = 24;
    private volatile int sendQuantumPerTick = 5;
    private volatile int loadQuantumPerTick = 4;
    private volatile int generateQuantumPerTick = 2;
    private volatile long tickSequence;
    private volatile boolean enabled = false;

    private AGCChunkBudget() {
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public void configure(final int sendsPerSecond, final int loadsPerSecond, final int generatesPerSecond) {
        this.sendsPerSecond = Math.max(1, sendsPerSecond);
        this.loadsPerSecond = Math.max(1, loadsPerSecond);
        this.generatesPerSecond = Math.max(1, generatesPerSecond);
        this.sendQuantumPerTick = Math.max(1, (this.sendsPerSecond + 19) / 20);
        this.loadQuantumPerTick = Math.max(1, (this.loadsPerSecond + 19) / 20);
        this.generateQuantumPerTick = Math.max(1, (this.generatesPerSecond + 19) / 20);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public boolean admit(final Player player, final Operation operation) {
        return player == null || this.admit(player.getUniqueId(), operation);
    }

    public boolean admit(final UUID playerId, final Operation operation) {
        if (!this.enabled || playerId == null) {
            this.admitted.incrementAndGet();
            return true;
        }
        final Operation op = operation == null ? Operation.LOAD : operation;
        final PlayerBudget budget = this.playerBudgets.computeIfAbsent(playerId, ignored -> new PlayerBudget(this.tickSequence));
        budget.refillIfNeeded(
            this.tickSequence,
            this.sendQuantumPerTick,
            this.loadQuantumPerTick,
            this.generateQuantumPerTick,
            this.sendsPerSecond * 2,
            this.loadsPerSecond * 2,
            this.generatesPerSecond
        );
        final boolean ok = switch (op) {
            case SEND -> budget.send.tryConsume(1);
            case LOAD -> budget.load.tryConsume(1);
            case GENERATE -> budget.generate.tryConsume(1);
        };
        if (ok) {
            this.admitted.incrementAndGet();
        } else {
            this.throttled.incrementAndGet();
        }
        return ok;
    }

    public void removePlayer(final Player player) {
        if (player != null) {
            this.removePlayer(player.getUniqueId());
        }
    }

    public void removePlayer(final UUID playerId) {
        if (playerId != null) {
            this.playerBudgets.remove(playerId);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.playerBudgets.size(), this.admitted.get(), this.throttled.get());
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCChunkBudget{enabled=" + snapshot.enabled()
            + ", trackedPlayers=" + snapshot.trackedPlayers()
            + ", admitted=" + snapshot.admitted()
            + ", throttled=" + snapshot.throttled()
            + ", tick=" + this.tickSequence
            + ", qpt(send/load/generate)=" + this.sendQuantumPerTick + '/' + this.loadQuantumPerTick + '/' + this.generateQuantumPerTick
            + '}';
    }

    private static final class PlayerBudget {
        private final Bucket send = new Bucket();
        private final Bucket load = new Bucket();
        private final Bucket generate = new Bucket();
        private long lastRefillTick;

        PlayerBudget(final long tickSequence) {
            this.lastRefillTick = Math.max(0L, tickSequence);
        }

        synchronized void refillIfNeeded(
            final long tickSequence,
            final int sendQuantum,
            final int loadQuantum,
            final int generateQuantum,
            final int sendBurst,
            final int loadBurst,
            final int generateBurst
        ) {
            final long elapsedTicks = Math.max(0L, tickSequence - this.lastRefillTick);
            if (elapsedTicks <= 0L) {
                this.send.initialise(sendBurst);
                this.load.initialise(loadBurst);
                this.generate.initialise(generateBurst);
                return;
            }
            this.lastRefillTick = tickSequence;
            this.send.refill(elapsedTicks, sendQuantum, sendBurst);
            this.load.refill(elapsedTicks, loadQuantum, loadBurst);
            this.generate.refill(elapsedTicks, generateQuantum, generateBurst);
        }
    }

    private static final class Bucket {
        private int tokens;
        private boolean initialised;

        void initialise(final int burst) {
            if (!this.initialised) {
                this.tokens = Math.max(1, burst);
                this.initialised = true;
            }
        }

        void refill(final long elapsedTicks, final int quantum, final int burst) {
            this.initialise(burst);
            final long refill = Math.min((long) Integer.MAX_VALUE, elapsedTicks * (long) Math.max(1, quantum));
            this.tokens = (int) Math.min((long) Math.max(1, burst), this.tokens + refill);
        }

        boolean tryConsume(final int cost) {
            if (this.tokens >= cost) {
                this.tokens -= cost;
                return true;
            }
            return false;
        }
    }

    public record Snapshot(boolean enabled, int trackedPlayers, long admitted, long throttled) {
    }
}
