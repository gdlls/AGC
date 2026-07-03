package io.agcmc.agc.api.compat;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Immutable compatibility declaration registered by a plugin. */
public final class AGCCompatibilityDeclaration {
    private final String pluginName;
    private final AGCCompatibilityScope scope;
    private final NamespacedKey key;
    private final EnumSet<AGCCompatibilityContract> contracts;
    private final Instant registeredAt;

    AGCCompatibilityDeclaration(
        final @NotNull Plugin plugin,
        final @NotNull AGCCompatibilityScope scope,
        final @Nullable NamespacedKey key,
        final @NotNull Set<AGCCompatibilityContract> contracts
    ) {
        this.pluginName = plugin.getName();
        this.scope = scope;
        this.key = key;
        this.contracts = contracts.isEmpty()
            ? EnumSet.noneOf(AGCCompatibilityContract.class)
            : EnumSet.copyOf(contracts);
        this.registeredAt = Instant.now();
    }

    public @NotNull String pluginName() { return this.pluginName; }
    public @NotNull AGCCompatibilityScope scope() { return this.scope; }
    public @Nullable NamespacedKey key() { return this.key; }
    public @NotNull Set<AGCCompatibilityContract> contracts() { return Collections.unmodifiableSet(this.contracts); }
    public @NotNull Instant registeredAt() { return this.registeredAt; }

    public boolean has(final @NotNull AGCCompatibilityContract contract) {
        return this.contracts.contains(contract);
    }

    @Override
    public String toString() {
        return "AGCCompatibilityDeclaration{"
            + "plugin='" + this.pluginName + '\''
            + ", scope=" + this.scope
            + ", key=" + this.key
            + ", contracts=" + this.contracts
            + ", registeredAt=" + this.registeredAt
            + '}';
    }
}
