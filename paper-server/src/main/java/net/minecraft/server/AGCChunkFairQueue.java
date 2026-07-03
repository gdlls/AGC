package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Per-player FIFO-preserving deficit queue for chunk load/generate/send starts.
 * <p>
 * This class does not own the real chunk queues and never reorders them. It only
 * tells the existing queue poller whether the head item for a player may start
 * this tick. If not, the item remains at its original position for a later poll.
 */
public final class AGCChunkFairQueue {
    public static final AGCChunkFairQueue INSTANCE = new AGCChunkFairQueue();

    private final ConcurrentHashMap<UUID, PlayerBudget> budgets = new ConcurrentHashMap<>();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong waited = new AtomicLong();
    private final AtomicLong nullPlayerAdmitted = new AtomicLong();
    private volatile int sendQuantum = 128;
    private volatile int loadQuantum = 96;
    private volatile int generateQuantum = 32;
    private volatile int maxCarryMultiplier = 4;
    private volatile long tickSequence;

    private AGCChunkFairQueue() {
    }

    public void configure(final int sendsPerSecond, final int loadsPerSecond, final int generatesPerSecond, final int maxCarryMultiplier) {
        this.sendQuantum = Math.max(1, sendsPerSecond / 20);
        this.loadQuantum = Math.max(1, loadsPerSecond / 20);
        this.generateQuantum = Math.max(1, generatesPerSecond / 20);
        this.maxCarryMultiplier = Math.max(1, maxCarryMultiplier);
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public void refillTick() {
        this.beginTick(this.tickSequence + 1L);
    }

    public Admission admit(final @Nullable UUID playerId, final AGCChunkBudget.Operation operation) {
        if (playerId == null || operation == null) {
            this.nullPlayerAdmitted.incrementAndGet();
            return new Admission(true, Integer.MAX_VALUE, "global/no-player ordered lane");
        }
        final PlayerBudget budget = this.budgets.computeIfAbsent(playerId, ignored -> new PlayerBudget(this.sendQuantum, this.loadQuantum, this.generateQuantum, this.tickSequence));
        budget.refillIfNeeded(this.tickSequence, this.sendQuantum, this.loadQuantum, this.generateQuantum, this.maxCarryMultiplier);
        final boolean ok = budget.tryConsume(operation);
        if (ok) {
            this.admitted.incrementAndGet();
        } else {
            this.waited.incrementAndGet();
        }
        return new Admission(ok, budget.tokens(operation), ok ? "fifo head admitted" : "fifo head waits for deficit token");
    }

    public void forget(final UUID playerId) {
        if (playerId != null) {
            this.budgets.remove(playerId);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this.admitted.get(), this.waited.get(), this.nullPlayerAdmitted.get(), this.budgets.size(), this.sendQuantum, this.loadQuantum, this.generateQuantum, this.maxCarryMultiplier);
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCChunkFairQueue{admitted=" + snapshot.admitted()
            + ", waited=" + snapshot.waited()
            + ", nullPlayerAdmitted=" + snapshot.nullPlayerAdmitted()
            + ", players=" + snapshot.players()
            + ", quantum(send/load/generate)=" + snapshot.sendQuantum() + '/' + snapshot.loadQuantum() + '/' + snapshot.generateQuantum()
            + ", maxCarryMultiplier=" + snapshot.maxCarryMultiplier()
            + ", tick=" + this.tickSequence
            + '}';
    }

    private static final class PlayerBudget {
        private int send;
        private int load;
        private int generate;
        private long lastRefillTick;

        PlayerBudget(final int send, final int load, final int generate, final long tickSequence) {
            this.send = Math.max(1, send);
            this.load = Math.max(1, load);
            this.generate = Math.max(1, generate);
            this.lastRefillTick = Math.max(0L, tickSequence);
        }

        synchronized void refillIfNeeded(final long tickSequence, final int sendQuantum, final int loadQuantum, final int generateQuantum, final int maxCarryMultiplier) {
            final long elapsedTicks = Math.max(0L, tickSequence - this.lastRefillTick);
            if (elapsedTicks <= 0L) {
                return;
            }
            this.lastRefillTick = tickSequence;
            final long sendRefill = Math.min((long) Integer.MAX_VALUE, elapsedTicks * (long) sendQuantum);
            final long loadRefill = Math.min((long) Integer.MAX_VALUE, elapsedTicks * (long) loadQuantum);
            final long generateRefill = Math.min((long) Integer.MAX_VALUE, elapsedTicks * (long) generateQuantum);
            this.send = (int) Math.min((long) sendQuantum * Math.max(1, maxCarryMultiplier), this.send + sendRefill);
            this.load = (int) Math.min((long) loadQuantum * Math.max(1, maxCarryMultiplier), this.load + loadRefill);
            this.generate = (int) Math.min((long) generateQuantum * Math.max(1, maxCarryMultiplier), this.generate + generateRefill);
        }

        synchronized boolean tryConsume(final AGCChunkBudget.Operation operation) {
            switch (operation) {
                case SEND -> {
                    if (this.send <= 0) return false;
                    this.send--;
                    return true;
                }
                case LOAD -> {
                    if (this.load <= 0) return false;
                    this.load--;
                    return true;
                }
                case GENERATE -> {
                    if (this.generate <= 0) return false;
                    this.generate--;
                    return true;
                }
                default -> {
                    return true;
                }
            }
        }

        synchronized int tokens(final AGCChunkBudget.Operation operation) {
            return switch (operation) {
                case SEND -> this.send;
                case LOAD -> this.load;
                case GENERATE -> this.generate;
            };
        }
    }

    public record Admission(boolean admitted, int remainingTokens, String reason) {
    }

    public record Snapshot(long admitted, long waited, long nullPlayerAdmitted, int players, int sendQuantum, int loadQuantum, int generateQuantum, int maxCarryMultiplier) {
    }
}
