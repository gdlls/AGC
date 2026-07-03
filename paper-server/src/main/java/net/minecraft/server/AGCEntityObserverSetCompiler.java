package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only observer-set compiler for alpha24 entity tracking preparation. */
public final class AGCEntityObserverSetCompiler {
    public static final AGCEntityObserverSetCompiler INSTANCE = new AGCEntityObserverSetCompiler();

    private final AtomicLong prepared = new AtomicLong();
    private final AtomicLong waiting = new AtomicLong();
    private volatile long tickSequence;
    private volatile long baseObserverTokens = 1_000_000L;
    private volatile long remainingObserverTokens = 1_000_000L;
    private volatile String lastReason = "cold";

    private AGCEntityObserverSetCompiler() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.baseObserverTokens = Math.max(262_144L, processors * 160_000L);
        this.remainingObserverTokens = this.baseObserverTokens;
        this.lastReason = "reset processors=" + processors;
    }

    public ObserverSet prepare(final UUID playerId, final int estimatedEntities, final int observerBands, final String reason) {
        final int entities = Math.max(0, estimatedEntities);
        final int bands = Math.max(1, observerBands);
        final int sets = Math.max(1, Math.min(8192, (int) Math.ceil(Math.sqrt(Math.max(1, entities) * bands))));
        final long cost = Math.max(1L, sets + entities / 96L);
        if (cost > this.remainingObserverTokens) {
            this.waiting.incrementAndGet();
            this.lastReason = "observer-set helper waits; entity tick/event semantics unchanged: " + safe(reason);
            return new ObserverSet(false, stableKey(playerId, entities, bands), sets, cost, this.remainingObserverTokens, this.lastReason);
        }
        this.remainingObserverTokens -= cost;
        this.prepared.incrementAndGet();
        this.lastReason = "prepared read-only entity observer sets=" + sets + " entities=" + entities;
        return new ObserverSet(true, stableKey(playerId, entities, bands), sets, cost, this.remainingObserverTokens, this.lastReason);
    }

    public String statusLine() {
        return "AGCEntityObserverSetCompiler{tick=" + this.tickSequence
            + ", prepared=" + this.prepared.get()
            + ", waiting=" + this.waiting.get()
            + ", baseTokens=" + this.baseObserverTokens
            + ", remainingTokens=" + this.remainingObserverTokens
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static long stableKey(final UUID playerId, final int entities, final int bands) {
        final long most = playerId == null ? 0L : playerId.getMostSignificantBits();
        final long least = playerId == null ? 0L : playerId.getLeastSignificantBits();
        return Long.rotateLeft(most, 5) ^ Long.rotateRight(least, 29) ^ ((long) entities << 8) ^ bands;
    }

    private static String safe(final String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    public record ObserverSet(boolean prepared, long stableKey, int sets, long cost, long remainingTokens, String reason) {}
}
