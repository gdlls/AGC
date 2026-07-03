package net.minecraft.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;

/**
 * Per-player packet budget model for AGC network backpressure.
 * <p>
 * The class prioritises movement/combat first, then chunks/light, cosmetics and
 * finally low-value metadata. Integrations can ask whether a packet class should
 * be sent now, delayed or coalesced without changing packet contents.
 */
public final class AGCPacketBudget {
    public static final AGCPacketBudget INSTANCE = new AGCPacketBudget();

    public enum Priority {
        MOVEMENT_COMBAT(4),
        CHUNK_LIGHT(3),
        COSMETIC(2),
        LOW_VALUE_METADATA(1);

        private final int weight;

        Priority(final int weight) {
            this.weight = weight;
        }

        public int weight() {
            return this.weight;
        }
    }

    private static final int DEFAULT_BYTES_PER_SECOND = 384 * 1024;
    private static final int DEFAULT_BURST_BYTES = 768 * 1024;

    private final Map<UUID, Budget> budgets = new ConcurrentHashMap<>();
    private final AtomicLong allowedPackets = new AtomicLong();
    private final AtomicLong delayedPackets = new AtomicLong();
    private volatile int bytesPerSecond = DEFAULT_BYTES_PER_SECOND;
    private volatile int burstBytes = DEFAULT_BURST_BYTES;
    private volatile boolean enabled = false;

    private AGCPacketBudget() {
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public void configure(final int bytesPerSecond, final int burstBytes) {
        this.bytesPerSecond = Math.max(16 * 1024, bytesPerSecond);
        this.burstBytes = Math.max(this.bytesPerSecond / 2, burstBytes);
    }

    public boolean allow(final Player player, final Priority priority, final int estimatedBytes) {
        return player == null || this.allow(player.getUniqueId(), priority, estimatedBytes);
    }

    public boolean allow(final UUID playerId, final Priority priority, final int estimatedBytes) {
        if (!this.enabled || playerId == null) {
            this.allowedPackets.incrementAndGet();
            return true;
        }
        final Priority effectivePriority = priority == null ? Priority.LOW_VALUE_METADATA : priority;
        final int cost = Math.max(1, estimatedBytes) / effectivePriority.weight();
        final Budget budget = this.budgets.computeIfAbsent(playerId, ignored -> new Budget(this.burstBytes));
        final boolean allowed = budget.tryConsume(cost, this.bytesPerSecond, this.burstBytes);
        if (allowed) {
            this.allowedPackets.incrementAndGet();
        } else {
            this.delayedPackets.incrementAndGet();
        }
        return allowed;
    }

    public void removePlayer(final Player player) {
        if (player != null) {
            this.removePlayer(player.getUniqueId());
        }
    }

    public void removePlayer(final UUID playerId) {
        if (playerId != null) {
            this.budgets.remove(playerId);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this.enabled, this.budgets.size(), this.allowedPackets.get(), this.delayedPackets.get(), this.bytesPerSecond, this.burstBytes);
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCPacketBudget{enabled=" + snapshot.enabled()
            + ", trackedPlayers=" + snapshot.trackedPlayers()
            + ", allowed=" + snapshot.allowedPackets()
            + ", delayed=" + snapshot.delayedPackets()
            + ", bytesPerSecond=" + snapshot.bytesPerSecond()
            + ", burstBytes=" + snapshot.burstBytes()
            + '}';
    }

    private static final class Budget {
        private int tokens;
        private long lastRefillNanos = System.nanoTime();

        Budget(final int initialTokens) {
            this.tokens = initialTokens;
        }

        synchronized boolean tryConsume(final int cost, final int bytesPerSecond, final int burstBytes) {
            final long now = System.nanoTime();
            final long elapsed = Math.max(0L, now - this.lastRefillNanos);
            final long refill = (elapsed * bytesPerSecond) / 1_000_000_000L;
            if (refill > 0L) {
                this.tokens = (int) Math.min((long) burstBytes, this.tokens + refill);
                this.lastRefillNanos = now;
            }
            if (this.tokens >= cost) {
                this.tokens -= cost;
                return true;
            }
            return false;
        }
    }

    public record Snapshot(boolean enabled, int trackedPlayers, long allowedPackets, long delayedPackets, int bytesPerSecond, int burstBytes) {
    }
}
