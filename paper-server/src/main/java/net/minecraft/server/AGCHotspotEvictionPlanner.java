package net.minecraft.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-local hotspot eviction for AGC helper caches. Eviction only affects
 * duplicate read-only planning data; it never unloads chunks, hides entities or
 * drops packets.
 */
public final class AGCHotspotEvictionPlanner {
    public static final AGCHotspotEvictionPlanner INSTANCE = new AGCHotspotEvictionPlanner();

    private final ConcurrentHashMap<Long, AtomicLong> hotspots = new ConcurrentHashMap<>();
    private final AtomicLong records = new AtomicLong();
    private final AtomicLong evictedPlans = new AtomicLong();
    private final AtomicLong protectedPlans = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile int maxHotspots = 262_144;
    private volatile int hotspotPlayers = 96;
    private volatile long tickSequence;

    private AGCHotspotEvictionPlanner() {
    }

    public void configure(final boolean enabled, final int maxHotspots, final int hotspotPlayers) {
        this.enabled = enabled;
        this.maxHotspots = Math.max(1, maxHotspots);
        this.hotspotPlayers = Math.max(1, hotspotPlayers);
    }

    public void beginTick(final long sequence) {
        if (sequence != this.tickSequence) {
            this.tickSequence = sequence;
            this.hotspots.clear();
        }
    }

    public Decision record(final long hotspotKey, final int players) {
        this.records.incrementAndGet();
        if (!this.enabled) {
            this.protectedPlans.incrementAndGet();
            return new Decision(false, "hotspot eviction disabled");
        }
        if (this.hotspots.size() >= this.maxHotspots && !this.hotspots.containsKey(hotspotKey)) {
            this.evictedPlans.incrementAndGet();
            return new Decision(true, "read-only hotspot plan evicted; gameplay state unchanged");
        }
        this.hotspots.computeIfAbsent(hotspotKey, ignored -> new AtomicLong()).addAndGet(Math.max(0, players));
        final long cost = Math.max(1L, Math.max(0, players) / Math.max(1, this.hotspotPlayers));
        final AGCScaleControlPlane.Admission control = AGCScaleControlPlane.INSTANCE.claim(AGCScaleControlPlane.Lane.HOTSPOT_EVICTION, cost, "hotspot " + hotspotKey);
        if (!control.admitted()) {
            this.evictedPlans.incrementAndGet();
            return new Decision(true, control.reason());
        }
        this.protectedPlans.incrementAndGet();
        return new Decision(false, "read-only hotspot plan retained for this tick");
    }

    public String statusLine() {
        return "AGCHotspotEvictionPlanner{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", hotspots=" + this.hotspots.size()
            + ", records=" + this.records.get()
            + ", evictedPlans=" + this.evictedPlans.get()
            + ", protectedPlans=" + this.protectedPlans.get()
            + '}';
    }

    public record Decision(boolean evictedReadOnlyPlan, String reason) {
    }
}
