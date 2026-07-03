package net.minecraft.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha17 algorithm kernel.
 * <p>
 * This is not a fallback switch. It is the budgeted control plane for concrete
 * algorithms that keep Minecraft/Paper semantics intact: predictive chunk hint
 * ordering, read-only entity interaction graphs, recipient cohort planning,
 * phase execution compilation and plugin semantic verification. It only grants
 * budget to invisible/read-only/helper work; Bukkit-visible mutation and plugin
 * callbacks remain ordered commits elsewhere.
 */
public final class AGCScale17AlgorithmKernel {
    public static final AGCScale17AlgorithmKernel INSTANCE = new AGCScale17AlgorithmKernel();

    public enum Plane {
        CHUNK_PREDICTIVE_INDEX,
        ENTITY_INTERACTION_GRAPH,
        NETWORK_RECIPIENT_COHORT,
        WORLD_PHASE_COMPILER,
        PLUGIN_SEMANTIC_VERIFY,
        CACHE_LOCALITY_WINDOW
    }

    private final EnumMap<Plane, AtomicLong> remaining = new EnumMap<>(Plane.class);
    private final EnumMap<Plane, AtomicLong> admitted = new EnumMap<>(Plane.class);
    private final EnumMap<Plane, AtomicLong> delayed = new EnumMap<>(Plane.class);
    private volatile boolean enabled = true;
    private volatile long tickSequence;
    private volatile long baseUnits = 720_000L;
    private volatile double pressureMultiplier = 1.0D;
    private volatile int expectedPlayers = 3000;
    private volatile int reservedCpuPercent = 28;

    private AGCScale17AlgorithmKernel() {
        for (final Plane plane : Plane.values()) {
            this.remaining.put(plane, new AtomicLong());
            this.admitted.put(plane, new AtomicLong());
            this.delayed.put(plane, new AtomicLong());
        }
    }

    public void configure(final boolean enabled, final long baseUnits, final int expectedPlayers, final int reservedCpuPercent) {
        this.enabled = enabled;
        this.baseUnits = Math.max(65_536L, baseUnits);
        this.expectedPlayers = Math.max(1, expectedPlayers);
        this.reservedCpuPercent = Math.max(5, Math.min(70, reservedCpuPercent));
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        final double scale16Pressure = AGCScale16ControlLaw.INSTANCE.pressure();
        this.pressureMultiplier = clamp(0.25D, 2.20D, scale16Pressure);
        final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int reservedCores = Math.max(1, (processors * this.reservedCpuPercent + 99) / 100);
        final int helperCores = Math.max(1, processors - reservedCores);
        final long playerFactor = Math.max(1L, Math.min(8L, (long) (this.expectedPlayers + 499) / 500L));
        final long total = this.enabled ? Math.max(8192L, Math.round(this.baseUnits * helperCores * playerFactor * this.pressureMultiplier)) : 0L;
        for (final Plane plane : Plane.values()) {
            final long share = switch (plane) {
                case CHUNK_PREDICTIVE_INDEX -> total * 24L / 100L;
                case ENTITY_INTERACTION_GRAPH -> total * 22L / 100L;
                case NETWORK_RECIPIENT_COHORT -> total * 22L / 100L;
                case WORLD_PHASE_COMPILER -> total * 12L / 100L;
                case PLUGIN_SEMANTIC_VERIFY -> total * 8L / 100L;
                case CACHE_LOCALITY_WINDOW -> total * 12L / 100L;
            };
            this.remaining.get(plane).set(Math.max(512L, share));
        }
    }

    public Admission claim(final Plane plane, final long units, final String reason) {
        if (!this.enabled) {
            this.delayed.get(plane).incrementAndGet();
            return new Admission(false, plane, 0L, "scale17 disabled; ordered semantics stay intact: " + safe(reason));
        }
        final long cost = Math.max(1L, units);
        final AtomicLong bucket = this.remaining.get(plane);
        while (true) {
            final long current = bucket.get();
            if (current < cost) {
                this.delayed.get(plane).incrementAndGet();
                return new Admission(false, plane, current, "scale17 waits; no gameplay semantics are skipped: " + safe(reason));
            }
            if (bucket.compareAndSet(current, current - cost)) {
                this.admitted.get(plane).incrementAndGet();
                return new Admission(true, plane, current - cost, safe(reason));
            }
        }
    }

    public String statusLine() {
        return "AGCScale17AlgorithmKernel{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", expectedPlayers=" + this.expectedPlayers
            + ", reservedCpuPercent=" + this.reservedCpuPercent
            + ", pressureMultiplier=" + this.pressureMultiplier
            + ", remaining=" + copy(this.remaining)
            + ", admitted=" + copy(this.admitted)
            + ", delayed=" + copy(this.delayed)
            + '}';
    }

    private static double clamp(final double min, final double max, final double value) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safe(final String value) {
        return value == null ? "" : value;
    }

    private static Map<Plane, Long> copy(final EnumMap<Plane, AtomicLong> source) {
        final EnumMap<Plane, Long> copy = new EnumMap<>(Plane.class);
        for (final Map.Entry<Plane, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(copy);
    }

    public record Admission(boolean admitted, Plane plane, long remainingUnits, String reason) {
    }
}
