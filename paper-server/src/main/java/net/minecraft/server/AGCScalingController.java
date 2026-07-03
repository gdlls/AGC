package net.minecraft.server;

import java.util.Collection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Adaptive budget profile controller for AGC opt-in scaling features.
 * <p>
 * Profiles do not turn experimental features on. They only retune budgets that
 * the operator already enabled, and they tighten semantic-preserving budgets in emergency conditions so a
 * bad profile cannot keep adding latency to an overloaded server.
 */
public final class AGCScalingController {
    public static final AGCScalingController INSTANCE = new AGCScalingController();

    public enum Profile {
        BASELINE,
        BALANCED,
        HIGH_DENSITY,
        THOUSAND_PLAYER_DENSE,
        EMERGENCY_SEMANTIC_GUARD
    }

    private volatile boolean enabled;
    private volatile int highDensityPlayers = 250;
    private volatile double emergencyMspt = 120.0D;
    private volatile Profile currentProfile = Profile.BASELINE;
    private volatile long samples;
    private volatile int lastOnlinePlayers;
    private volatile double lastMspt;
    private volatile long lastPendingFlushes;
    private volatile long lastPendingTranslatorTasks;

    private AGCScalingController() {
    }

    public void configure(final boolean enabled, final int highDensityPlayers, final double emergencyMspt) {
        this.enabled = enabled;
        this.highDensityPlayers = Math.max(1, highDensityPlayers);
        this.emergencyMspt = Math.max(50.0D, emergencyMspt);
        if (!enabled && this.currentProfile != Profile.BASELINE) {
            this.currentProfile = Profile.BASELINE;
            AGCStabilityJournal.INSTANCE.record("profile", "adaptive profiles disabled; using baseline configured budgets");
        }
    }

    public Profile sampleAndApply(final double mspt, final long pendingFlushes, final long pendingTranslatorTasks) {
        this.samples++;
        this.lastMspt = Math.max(0.0D, mspt);
        this.lastPendingFlushes = Math.max(0L, pendingFlushes);
        this.lastPendingTranslatorTasks = Math.max(0L, pendingTranslatorTasks);
        this.lastOnlinePlayers = onlinePlayers();

        final Profile next = this.enabled ? this.chooseProfile() : Profile.BASELINE;
        final Profile previous = this.currentProfile;
        if (previous != next) {
            this.currentProfile = next;
            AGCStabilityJournal.INSTANCE.record(
                "profile",
                "changed " + previous + " -> " + next
                    + " players=" + this.lastOnlinePlayers
                    + " mspt=" + this.lastMspt
                    + " pendingFlushes=" + this.lastPendingFlushes
                    + " pendingTranslator=" + this.lastPendingTranslatorTasks
            );
        }
        if (this.enabled) {
            this.applyProfile(next);
        }
        return next;
    }

    public Profile currentProfile() {
        return this.currentProfile;
    }

    private Profile chooseProfile() {
        if (this.lastMspt >= this.emergencyMspt || this.lastPendingTranslatorTasks >= 8192L) {
            return Profile.EMERGENCY_SEMANTIC_GUARD;
        }
        if (this.lastOnlinePlayers >= Math.max(768, this.highDensityPlayers * 8) || this.lastPendingFlushes >= 131_072L) {
            return Profile.THOUSAND_PLAYER_DENSE;
        }
        if (this.lastOnlinePlayers >= this.highDensityPlayers || this.lastPendingFlushes >= 32768L) {
            return Profile.HIGH_DENSITY;
        }
        if (this.lastOnlinePlayers >= Math.max(32, this.highDensityPlayers / 3)) {
            return Profile.BALANCED;
        }
        return Profile.BASELINE;
    }

    private void applyProfile(final Profile profile) {
        switch (profile) {
            case BASELINE -> {
                AGCPacketBudget.INSTANCE.configure(384 * 1024, 768 * 1024);
                AGCChunkBudget.INSTANCE.configure(96, 80, 24);
                AGCNetworkAdmission.INSTANCE.configureDeferredFlushLimits(16_384, 8_192);
            }
            case BALANCED -> {
                AGCPacketBudget.INSTANCE.configure(448 * 1024, 896 * 1024);
                AGCChunkBudget.INSTANCE.configure(112, 88, 28);
                AGCNetworkAdmission.INSTANCE.configureDeferredFlushLimits(24_576, 12_288);
            }
            case HIGH_DENSITY -> {
                AGCPacketBudget.INSTANCE.configure(320 * 1024, 640 * 1024);
                AGCChunkBudget.INSTANCE.configure(80, 64, 18);
                AGCNetworkAdmission.INSTANCE.configureDeferredFlushLimits(65_536, 32_768);
            }
            case THOUSAND_PLAYER_DENSE -> {
                // Thousands of players create duplicate low-value fanout and chunk-view churn.
                // Keep gameplay packets immediate, but bias invisible work toward wider coalescing
                // and stronger per-player chunk fairness so one hotspot cannot monopolise the tick.
                AGCPacketBudget.INSTANCE.configure(288 * 1024, 576 * 1024);
                AGCChunkBudget.INSTANCE.configure(72, 56, 16);
                AGCNetworkAdmission.INSTANCE.configureDeferredFlushLimits(131_072, 65_536);
            }
            case EMERGENCY_SEMANTIC_GUARD -> {
                // Do not add more delay while overloaded: drain aggressively and
                // keep pending queue small so new writes preserve immediate flush.
                AGCPacketBudget.INSTANCE.configure(256 * 1024, 512 * 1024);
                AGCChunkBudget.INSTANCE.configure(48, 40, 12);
                AGCNetworkAdmission.INSTANCE.configureDeferredFlushLimits(4_096, 65_536);
            }
        }
    }

    private static int onlinePlayers() {
        try {
            final Collection<? extends Player> players = Bukkit.getOnlinePlayers();
            return players == null ? 0 : players.size();
        } catch (final Throwable ignored) {
            return 0;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.enabled,
            this.currentProfile,
            this.samples,
            this.lastOnlinePlayers,
            this.lastMspt,
            this.lastPendingFlushes,
            this.lastPendingTranslatorTasks,
            this.highDensityPlayers,
            this.emergencyMspt
        );
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCScalingController{enabled=" + snapshot.enabled()
            + ", profile=" + snapshot.profile()
            + ", samples=" + snapshot.samples()
            + ", players=" + snapshot.onlinePlayers()
            + ", mspt=" + snapshot.mspt()
            + ", pendingFlushes=" + snapshot.pendingFlushes()
            + ", pendingTranslator=" + snapshot.pendingTranslatorTasks()
            + ", highDensityPlayers=" + snapshot.highDensityPlayers()
            + ", emergencyMspt=" + snapshot.emergencyMspt()
            + '}';
    }

    public record Snapshot(
        boolean enabled,
        Profile profile,
        long samples,
        int onlinePlayers,
        double mspt,
        long pendingFlushes,
        long pendingTranslatorTasks,
        int highDensityPlayers,
        double emergencyMspt
    ) {
    }
}
