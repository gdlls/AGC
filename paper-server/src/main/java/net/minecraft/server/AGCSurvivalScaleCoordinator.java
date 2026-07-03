package net.minecraft.server;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-density survival coordinator for AGC alpha12.
 * <p>
 * The target is thousands of normal survival players at 20 TPS without reducing
 * gameplay semantics. The coordinator therefore optimises invisible work only:
 * deduplicated interest/fanout preparation, global fairness, CPU/memory-aware
 * budget shaping, and deterministic commit pressure accounting.
 */
public final class AGCSurvivalScaleCoordinator {
    public static final AGCSurvivalScaleCoordinator INSTANCE = new AGCSurvivalScaleCoordinator();

    public enum Mode {
        NORMAL_SURVIVAL,
        DENSE_SURVIVAL,
        THOUSAND_PLAYER_SURVIVAL,
        HOTSPOT_CONTROL
    }

    private final AtomicLong survivalClaims = new AtomicLong();
    private final AtomicLong survivalAdmitted = new AtomicLong();
    private final AtomicLong survivalWaits = new AtomicLong();

    private volatile boolean enabled = true;
    private volatile int targetPlayers = 3000;
    private volatile double targetTps = 20.0D;
    private volatile int hotspotPlayersPerCell = 48;
    private volatile long tickSequence;
    private volatile Mode mode = Mode.NORMAL_SURVIVAL;
    private volatile double lastMspt;
    private volatile int lastPlayers;
    private volatile int lastWorlds;
    private volatile int lastChunks;
    private volatile int lastEntities;
    private volatile String lastDecision = "initial";

    private AGCSurvivalScaleCoordinator() {
    }

    public void configure(final boolean enabled, final int targetPlayers, final double targetTps, final int hotspotPlayersPerCell) {
        this.enabled = enabled;
        this.targetPlayers = Math.max(1, targetPlayers);
        this.targetTps = Math.max(1.0D, targetTps);
        this.hotspotPlayersPerCell = Math.max(1, hotspotPlayersPerCell);
    }

    public void beginTick(
        final long sequence,
        final int onlinePlayers,
        final int activeWorlds,
        final int loadedChunks,
        final int trackedEntities,
        final double mspt
    ) {
        this.tickSequence = Math.max(0L, sequence);
        this.lastPlayers = Math.max(0, onlinePlayers);
        this.lastWorlds = Math.max(0, activeWorlds);
        this.lastChunks = Math.max(0, loadedChunks);
        this.lastEntities = Math.max(0, trackedEntities);
        this.lastMspt = Math.max(0.0D, mspt);
        this.mode = classify(this.lastPlayers, this.lastMspt);
        AGCResourceEfficiencyEngine.INSTANCE.beginTick(sequence, this.lastPlayers, this.lastWorlds, this.lastChunks, this.lastEntities, this.lastMspt);
        AGCInterestGraph.INSTANCE.beginTick(sequence);
        AGCGlobalFairnessMatrix.INSTANCE.beginTick(sequence);
        this.lastDecision = "mode=" + this.mode + " targetPlayers=" + this.targetPlayers + " targetTps=" + this.targetTps;
    }

    public AGCResourceEfficiencyEngine.Grant claimInvisibleWork(
        final AGCResourceEfficiencyEngine.Domain domain,
        final UUID playerId,
        final long cost,
        final String reason
    ) {
        this.survivalClaims.incrementAndGet();
        if (!this.enabled) {
            this.survivalAdmitted.incrementAndGet();
            return new AGCResourceEfficiencyEngine.Grant(true, Long.MAX_VALUE, "survival coordinator disabled: " + nullToEmpty(reason));
        }
        final AGCResourceEfficiencyEngine.Grant resource = AGCResourceEfficiencyEngine.INSTANCE.claim(domain, cost, false, reason);
        if (!resource.admitted()) {
            this.survivalWaits.incrementAndGet();
            return resource;
        }
        if (playerId != null) {
            final AGCGlobalFairnessMatrix.Admission fairness = AGCGlobalFairnessMatrix.INSTANCE.admitPlayer(playerId, Math.max(1L, cost), reason);
            if (!fairness.admitted()) {
                this.survivalWaits.incrementAndGet();
                return new AGCResourceEfficiencyEngine.Grant(false, fairness.remaining(), fairness.reason());
            }
        }
        this.survivalAdmitted.incrementAndGet();
        return resource;
    }

    public AGCInterestGraph.FanoutPlan planPlayerFanout(final UUID playerId, final int estimatedRecipients, final String reason) {
        return AGCInterestGraph.INSTANCE.planFanout(playerId, estimatedRecipients, reason);
    }

    public Mode mode() {
        return this.mode;
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.tickSequence,
            this.targetPlayers,
            this.targetTps,
            this.hotspotPlayersPerCell,
            this.mode,
            this.lastPlayers,
            this.lastWorlds,
            this.lastChunks,
            this.lastEntities,
            this.lastMspt,
            this.survivalClaims.get(),
            this.survivalAdmitted.get(),
            this.survivalWaits.get(),
            this.lastDecision
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCSurvivalScaleCoordinator{enabled=" + snapshot.enabled()
            + ", tick=" + snapshot.tickSequence()
            + ", mode=" + snapshot.mode()
            + ", targetPlayers=" + snapshot.targetPlayers()
            + ", targetTps=" + snapshot.targetTps()
            + ", players=" + snapshot.players()
            + ", worlds=" + snapshot.worlds()
            + ", chunks=" + snapshot.chunks()
            + ", entities=" + snapshot.entities()
            + ", mspt=" + snapshot.mspt()
            + ", claims=" + snapshot.claims()
            + ", admitted=" + snapshot.admitted()
            + ", waits=" + snapshot.waits()
            + ", decision=" + snapshot.lastDecision()
            + '}';
    }

    private Mode classify(final int players, final double mspt) {
        if (players >= this.targetPlayers || mspt >= 45.0D) {
            return Mode.HOTSPOT_CONTROL;
        }
        if (players >= Math.max(1000, this.targetPlayers / 2) || mspt >= 38.0D) {
            return Mode.THOUSAND_PLAYER_SURVIVAL;
        }
        if (players >= Math.max(250, this.targetPlayers / 6) || mspt >= 30.0D) {
            return Mode.DENSE_SURVIVAL;
        }
        return Mode.NORMAL_SURVIVAL;
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    public record Snapshot(
        boolean enabled,
        long tickSequence,
        int targetPlayers,
        double targetTps,
        int hotspotPlayersPerCell,
        Mode mode,
        int players,
        int worlds,
        int chunks,
        int entities,
        double mspt,
        long claims,
        long admitted,
        long waits,
        String lastDecision
    ) {
    }
}
