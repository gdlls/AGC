package net.minecraft.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Semantic contract table for plugin-facing logic categories. */
public final class AGCPluginLogicTranslator {
    public static final AGCPluginLogicTranslator INSTANCE = new AGCPluginLogicTranslator();

    public enum Logic {
        LISTENER_CALLBACK,
        SCHEDULER_TASK,
        SCOREBOARD_UPDATE,
        INVENTORY_MUTATION,
        COSMETIC_PACKET,
        DATABASE_RESULT,
        MINIGAME_STATE_COMMIT
    }

    private final ConcurrentHashMap<String, Logic> owners = new ConcurrentHashMap<>();
    private final AtomicLong declared = new AtomicLong();
    private final AtomicLong primaryRequired = new AtomicLong();
    private final AtomicLong readOnlyAllowed = new AtomicLong();
    private volatile boolean enabled = true;

    private AGCPluginLogicTranslator() {
    }

    public void configure(final boolean enabled) {
        this.enabled = enabled;
    }

    public void declare(final String owner, final Logic logic) {
        if (owner == null || owner.isBlank() || logic == null) {
            return;
        }
        this.owners.put(owner, logic);
        this.declared.incrementAndGet();
    }

    public AGCThreadAffinityTranslator.Translation translate(final String owner, final Logic logic, final String reason) {
        final Logic effective = logic == null ? this.owners.getOrDefault(owner, Logic.LISTENER_CALLBACK) : logic;
        final AGCThreadAffinityTranslator.Phase phase = switch (effective) {
            case COSMETIC_PACKET -> AGCThreadAffinityTranslator.Phase.DEADLINE_PACKET_PLAN;
            case DATABASE_RESULT -> AGCThreadAffinityTranslator.Phase.PURE_COMPUTE_PLAN;
            case LISTENER_CALLBACK, SCHEDULER_TASK, SCOREBOARD_UPDATE, INVENTORY_MUTATION, MINIGAME_STATE_COMMIT -> AGCThreadAffinityTranslator.Phase.ORDERED_PRIMARY_COMMIT;
        };
        if (phase == AGCThreadAffinityTranslator.Phase.ORDERED_PRIMARY_COMMIT) {
            this.primaryRequired.incrementAndGet();
        } else {
            this.readOnlyAllowed.incrementAndGet();
        }
        return AGCThreadAffinityTranslator.INSTANCE.translate(phase, owner, reason);
    }

    public String statusLine() {
        return "AGCPluginLogicTranslator{enabled=" + this.enabled
            + ", owners=" + this.owners.size()
            + ", declared=" + this.declared.get()
            + ", primaryRequired=" + this.primaryRequired.get()
            + ", readOnlyAllowed=" + this.readOnlyAllowed.get()
            + '}';
    }
}
