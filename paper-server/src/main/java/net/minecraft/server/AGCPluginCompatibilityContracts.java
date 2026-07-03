package net.minecraft.server;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Internal mirror of plugin-declared AGC compatibility contracts. */
public final class AGCPluginCompatibilityContracts {
    public static final AGCPluginCompatibilityContracts INSTANCE = new AGCPluginCompatibilityContracts();

    public enum Contract {
        WORLD_LOCAL_EVENTS,
        ARENA_READ_ONLY_PREPARE,
        DEADLINE_ORDERED_COSMETIC_PACKETS,
        FIFO_CHUNK_INTENTS,
        ARENA_VISIBILITY_ISOLATION,
        READ_ONLY_ENTITY_SNAPSHOTS,
        PRIMARY_THREAD_COMMIT_DISCIPLINE
    }

    private final Map<String, EnumSet<Contract>> declarations = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile boolean enabled = true;

    private AGCPluginCompatibilityContracts() {
    }

    public void configure(final boolean enabled) {
        this.enabled = enabled;
    }

    public void declare(final String pluginName, final Set<Contract> contracts) {
        if (pluginName == null || pluginName.isBlank()) {
            return;
        }
        final EnumSet<Contract> copy = contracts == null || contracts.isEmpty()
            ? EnumSet.noneOf(Contract.class)
            : EnumSet.copyOf(contracts);
        this.declarations.put(pluginName, copy);
        AGCSemanticInvariant.INSTANCE.record(AGCSemanticInvariant.Domain.PLUGIN, AGCSemanticInvariant.Decision.MAIN_THREAD_ORDERED_COMMIT, "compat-contract plugin=" + pluginName + " contracts=" + copy);
        AGCCompatibilityBridge.invalidatePluginScan();
    }

    public boolean has(final String pluginName, final Contract contract) {
        if (!this.enabled || pluginName == null || contract == null) {
            return false;
        }
        final EnumSet<Contract> set = this.declarations.get(pluginName);
        return set != null && set.contains(contract);
    }

    public Snapshot snapshot() {
        int total = 0;
        synchronized (this.declarations) {
            for (final EnumSet<Contract> set : this.declarations.values()) {
                total += set.size();
            }
            return new Snapshot(this.enabled, this.declarations.size(), total);
        }
    }

    public String statusLine() {
        final Snapshot snapshot = this.snapshot();
        return "AGCPluginCompatibilityContracts{enabled=" + snapshot.enabled()
            + ", plugins=" + snapshot.plugins()
            + ", contracts=" + snapshot.contracts()
            + '}';
    }

    public record Snapshot(boolean enabled, int plugins, int contracts) {
    }
}
