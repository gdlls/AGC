package net.minecraft.server;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backpressure bridge for the plugin logic translators. It makes plugin-facing
 * helper work obey the same no-invasion control plane: read-only and pure
 * compute plans may use spare capacity; Bukkit-visible work is translated to an
 * ordered primary-thread commit lane instead of running off-thread.
 */
public final class AGCPluginBackpressureBridge {
    public static final AGCPluginBackpressureBridge INSTANCE = new AGCPluginBackpressureBridge();

    public enum Operation {
        READ_ONLY_QUERY,
        PURE_COMPUTE,
        PACKET_PLAN,
        CHUNK_INTENT,
        ORDERED_COMMIT,
        FORBIDDEN_MUTATION
    }

    public enum Route {
        READ_ONLY_PREPARE,
        DEADLINE_PLAN,
        FIFO_INTENT,
        PRIMARY_THREAD_COMMIT,
        REJECT_OFF_THREAD_MUTATION
    }

    private final EnumMap<Route, AtomicLong> routed = new EnumMap<>(Route.class);
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();
    private volatile boolean enabled = true;
    private volatile long maxPendingTranslatedTasks = 65_536L;
    private volatile long pendingTranslatedTasks;
    private volatile long tickSequence;

    private AGCPluginBackpressureBridge() {
        for (final Route route : Route.values()) {
            this.routed.put(route, new AtomicLong());
        }
    }

    public void configure(final boolean enabled, final long maxPendingTranslatedTasks) {
        this.enabled = enabled;
        this.maxPendingTranslatedTasks = Math.max(1L, maxPendingTranslatedTasks);
    }

    public void beginTick(final long sequence, final long pendingTranslatedTasks) {
        this.tickSequence = Math.max(0L, sequence);
        this.pendingTranslatedTasks = Math.max(0L, pendingTranslatedTasks);
    }

    public Decision route(final Operation operation, final long units, final String owner) {
        this.requests.incrementAndGet();
        final Route route = switch (operation == null ? Operation.ORDERED_COMMIT : operation) {
            case READ_ONLY_QUERY, PURE_COMPUTE -> Route.READ_ONLY_PREPARE;
            case PACKET_PLAN -> Route.DEADLINE_PLAN;
            case CHUNK_INTENT -> Route.FIFO_INTENT;
            case ORDERED_COMMIT -> Route.PRIMARY_THREAD_COMMIT;
            case FORBIDDEN_MUTATION -> Route.REJECT_OFF_THREAD_MUTATION;
        };
        if (route == Route.REJECT_OFF_THREAD_MUTATION) {
            this.routed.get(route).incrementAndGet();
            return new Decision(false, route, "off-thread Bukkit mutation is not translated into async work: " + safe(owner));
        }
        if (route == Route.PRIMARY_THREAD_COMMIT) {
            this.routed.get(route).incrementAndGet();
            return new Decision(true, route, "Bukkit-visible plugin work remains primary-thread ordered: " + safe(owner));
        }
        if (!this.enabled || this.pendingTranslatedTasks >= this.maxPendingTranslatedTasks) {
            this.delayed.incrementAndGet();
            return new Decision(false, Route.PRIMARY_THREAD_COMMIT, "translator backpressure keeps plugin semantics ordered: " + safe(owner));
        }
        final AGCScaleControlPlane.Admission control = AGCScaleControlPlane.INSTANCE.claim(AGCScaleControlPlane.Lane.PLUGIN_TRANSLATION, Math.max(1L, units), owner);
        if (!control.admitted()) {
            this.delayed.incrementAndGet();
            return new Decision(false, Route.PRIMARY_THREAD_COMMIT, control.reason());
        }
        this.routed.get(route).incrementAndGet();
        return new Decision(true, route, "plugin helper work routed without changing Bukkit event order: " + safe(owner));
    }

    public String statusLine() {
        return "AGCPluginBackpressureBridge{enabled=" + this.enabled
            + ", tick=" + this.tickSequence
            + ", pendingTranslatedTasks=" + this.pendingTranslatedTasks
            + ", maxPending=" + this.maxPendingTranslatedTasks
            + ", requests=" + this.requests.get()
            + ", delayed=" + this.delayed.get()
            + ", routed=" + this.routed
            + '}';
    }

    private static String safe(final String value) {
        return value == null ? "unknown" : value;
    }

    public record Decision(boolean admitted, Route route, String reason) {
    }
}
