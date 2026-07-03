package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local read-only entity interaction graph.
 * <p>
 * The graph reduces duplicate candidate work for dense survival hotspots. It is
 * deliberately not an entity scheduler: it never skips entity ticks, AI,
 * damage, collisions, interactions or Bukkit events.
 */
public final class AGCEntityInteractionGraph {
    public static final AGCEntityInteractionGraph INSTANCE = new AGCEntityInteractionGraph();

    private final AtomicLong prepared = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private final AtomicLong candidateEdges = new AtomicLong();
    private volatile long tickSequence;
    private volatile int maxCandidatesPerPlayer = 65_536;
    private volatile int bandSize = 64;

    private AGCEntityInteractionGraph() {
    }

    public void configure(final int maxCandidatesPerPlayer, final int bandSize) {
        this.maxCandidatesPerPlayer = Math.max(256, Math.min(262_144, maxCandidatesPerPlayer));
        this.bandSize = Math.max(16, Math.min(512, bandSize));
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
    }

    public Admission prepare(final UUID playerId, final int estimatedEntities, final String regionKey) {
        final int candidates = Math.max(0, Math.min(this.maxCandidatesPerPlayer, estimatedEntities));
        final long bands = Math.max(1L, (candidates + this.bandSize - 1L) / this.bandSize);
        final long cost = Math.max(1L, bands + candidates / 16L);
        final AGCScale17AlgorithmKernel.Admission budget = AGCScale17AlgorithmKernel.INSTANCE.claim(
            AGCScale17AlgorithmKernel.Plane.ENTITY_INTERACTION_GRAPH,
            cost,
            regionKey
        );
        if (!budget.admitted()) {
            this.waits.incrementAndGet();
            return new Admission(false, candidates, bands, 0L, "entity graph waits; live entity ticks continue ordered: " + budget.reason());
        }
        final long edges = Math.min((long) candidates * 4L, bands * this.bandSize * 2L);
        this.prepared.incrementAndGet();
        this.candidateEdges.addAndGet(edges);
        return new Admission(true, candidates, bands, edges, "read-only interaction candidates");
    }

    public String statusLine() {
        return "AGCEntityInteractionGraph{tick=" + this.tickSequence
            + ", maxCandidatesPerPlayer=" + this.maxCandidatesPerPlayer
            + ", bandSize=" + this.bandSize
            + ", prepared=" + this.prepared.get()
            + ", waits=" + this.waits.get()
            + ", candidateEdges=" + this.candidateEdges.get()
            + '}';
    }

    public record Admission(boolean admitted, int candidates, long bands, long candidateEdges, String reason) {
    }
}
