package net.minecraft.server;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alpha24 plugin semantic ticket cache.
 *
 * Caches only classification tickets. It never merges events, never changes
 * callback order and never authorises off-thread Bukkit mutation.
 */
public final class AGCPluginDeterministicTicketCache {
    public static final AGCPluginDeterministicTicketCache INSTANCE = new AGCPluginDeterministicTicketCache();

    private final ConcurrentHashMap<Long, Ticket> tickets = new ConcurrentHashMap<>();
    private final AtomicLong readOnly = new AtomicLong();
    private final AtomicLong ordered = new AtomicLong();
    private final AtomicLong forbidden = new AtomicLong();
    private volatile long tickSequence;
    private volatile String lastReason = "cold";

    private AGCPluginDeterministicTicketCache() {}

    public void beginTick(final long sequence) {
        this.tickSequence = Math.max(0L, sequence);
        this.tickets.clear();
        this.lastReason = "reset";
    }

    public Ticket classify(final String pluginName, final String operation, final boolean declaredReadOnly, final long cost) {
        final String plugin = pluginName == null || pluginName.isBlank() ? "unknown" : pluginName;
        final String op = operation == null || operation.isBlank() ? "unknown" : operation;
        final String lower = op.toLowerCase(Locale.ROOT);
        final long key = stableKey(plugin, lower, declaredReadOnly);
        final Ticket cached = this.tickets.get(key);
        if (cached != null) {
            this.lastReason = "plugin semantic ticket cache hit plugin=" + plugin + " op=" + op;
            return cached;
        }
        final Ticket ticket;
        if (lower.contains("offthread") || lower.contains("asyncmutate") || lower.contains("forbidden")) {
            this.forbidden.incrementAndGet();
            ticket = new Ticket(false, false, true, 0L, key, "forbidden off-thread Bukkit mutation plugin=" + plugin + " op=" + op);
        } else if (visibleMutation(lower)) {
            this.ordered.incrementAndGet();
            ticket = new Ticket(false, true, false, Math.max(1L, Math.min(8192L, cost)), key, "Bukkit-visible plugin work remains ordered plugin=" + plugin + " op=" + op);
        } else if (declaredReadOnly || lower.contains("readonly") || lower.contains("pure") || lower.contains("plan") || lower.contains("classify")) {
            this.readOnly.incrementAndGet();
            ticket = new Ticket(true, false, false, Math.max(1L, Math.min(65_536L, Math.max(1L, cost / 2L))), key, "cached read-only plugin semantic ticket plugin=" + plugin + " op=" + op);
        } else {
            this.ordered.incrementAndGet();
            ticket = new Ticket(false, true, false, Math.max(1L, Math.min(8192L, cost)), key, "unknown plugin work kept ordered plugin=" + plugin + " op=" + op);
        }
        this.tickets.put(key, ticket);
        this.lastReason = ticket.reason();
        return ticket;
    }

    public String statusLine() {
        return "AGCPluginDeterministicTicketCache{tick=" + this.tickSequence
            + ", tickets=" + this.tickets.size()
            + ", readOnly=" + this.readOnly.get()
            + ", ordered=" + this.ordered.get()
            + ", forbidden=" + this.forbidden.get()
            + ", lastReason='" + this.lastReason + "'}";
    }

    private static boolean visibleMutation(final String lower) {
        return lower.contains("setblock")
            || lower.contains("teleport")
            || lower.contains("spawn")
            || lower.contains("inventory")
            || lower.contains("scoreboard")
            || lower.contains("command")
            || lower.contains("event")
            || lower.contains("world")
            || lower.contains("blockbreak")
            || lower.contains("blockplace")
            || lower.contains("damage")
            || lower.contains("interaction");
    }

    private static long stableKey(final String plugin, final String op, final boolean readOnly) {
        long h = readOnly ? 0x1234abcd5678ef90L : 0xfedcba9876543210L;
        final String combined = plugin + ':' + op;
        for (int i = 0; i < combined.length(); i++) {
            h ^= combined.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    public record Ticket(boolean readOnlyPrepare, boolean orderedCommit, boolean forbiddenMutation, long tickets, long stableKey, String reason) {}
}
