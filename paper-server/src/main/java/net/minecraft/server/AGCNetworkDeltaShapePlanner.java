package net.minecraft.server;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lossless packet delta/shape planner. It only shares immutable classification
 * and serializer planning for equivalent packet shapes; it never merges mutable
 * packet instances, drops packets, or reorders a player's connection stream.
 */
public final class AGCNetworkDeltaShapePlanner {
    public static final AGCNetworkDeltaShapePlanner INSTANCE = new AGCNetworkDeltaShapePlanner();

    private final ConcurrentMap<String, Long> shapes = new ConcurrentHashMap<>();
    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong orderedInteractive = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private volatile long tickSequence;
    private volatile int maxShapesPerTick = 262_144;

    private AGCNetworkDeltaShapePlanner() {
    }

    public void configure(final int maxShapesPerTick) {
        this.maxShapesPerTick = Math.max(1024, Math.min(2_000_000, maxShapesPerTick));
    }

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.shapes.clear();
    }

    public Decision plan(final String packetName, final boolean interactive, final long fanout, final long pendingDeferredFlushes) {
        final String name = packetName == null ? "unknown" : packetName;
        if (interactive || isInteractiveName(name)) {
            this.orderedInteractive.incrementAndGet();
            return new Decision(false, true, "interactive packet keeps immediate ordered write: " + name);
        }
        if (this.shapes.size() >= this.maxShapesPerTick) {
            this.waits.incrementAndGet();
            return new Decision(false, true, "packet shape table saturated; keep ordered write");
        }
        final long cost = Math.max(1L, 1L + fanout / 128L + pendingDeferredFlushes / 512L);
        final AGCScale16ControlLaw.Admission budget = AGCScale16ControlLaw.INSTANCE.claim(AGCScale16ControlLaw.Axis.NETWORK_DELTA, cost, name);
        if (!budget.admitted()) {
            this.waits.incrementAndGet();
            return new Decision(false, true, budget.reason());
        }
        this.shapes.merge(shapeKey(name), 1L, Long::sum);
        this.planned.incrementAndGet();
        return new Decision(true, false, "lossless delta shape planned for " + name);
    }

    public String statusLine() {
        return "AGCNetworkDeltaShapePlanner{tick=" + this.tickSequence
            + ", shapes=" + this.shapes.size()
            + ", maxShapesPerTick=" + this.maxShapesPerTick
            + ", planned=" + this.planned.get()
            + ", orderedInteractive=" + this.orderedInteractive.get()
            + ", waits=" + this.waits.get()
            + '}';
    }

    private static String shapeKey(final String name) {
        final int dollar = name.indexOf('$');
        final String base = dollar >= 0 ? name.substring(0, dollar) : name;
        return base.toLowerCase(Locale.ROOT);
    }

    private static boolean isInteractiveName(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("move")
            || lower.contains("position")
            || lower.contains("teleport")
            || lower.contains("attack")
            || lower.contains("interact")
            || lower.contains("inventory")
            || lower.contains("keepalive")
            || lower.contains("chunk")
            || lower.contains("light");
    }

    public record Decision(boolean planned, boolean orderedNow, String reason) {
    }
}
