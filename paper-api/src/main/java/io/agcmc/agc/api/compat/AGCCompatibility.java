package io.agcmc.agc.api.compat;

import io.agcmc.agc.api.AGC;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Public compatibility contract registry for plugins.
 * <p>
 * This is intentionally opt-in and conservative: declarations help AGC choose
 * no-invasion lanes, but never allow off-thread Bukkit mutation or event-order
 * changes.
 */
public final class AGCCompatibility {
    public AGCCompatibility() {
    }

    private final Map<String, AGCCompatibilityDeclaration> declarations = new LinkedHashMap<>();

    public synchronized @NotNull AGCCompatibilityDeclaration declare(
        final @NotNull Plugin plugin,
        final @NotNull AGCCompatibilityScope scope,
        final @Nullable NamespacedKey key,
        final @NotNull Collection<AGCCompatibilityContract> contracts
    ) {
        AGC.scheduler().ensurePrimaryThread("compatibility.declare");
        final EnumSet<AGCCompatibilityContract> copy = contracts.isEmpty()
            ? EnumSet.noneOf(AGCCompatibilityContract.class)
            : EnumSet.copyOf(contracts);
        final AGCCompatibilityDeclaration declaration = new AGCCompatibilityDeclaration(plugin, scope, key, copy);
        this.declarations.put(id(plugin.getName(), scope, key), declaration);
        return declaration;
    }

    public synchronized void revoke(final @NotNull Plugin plugin, final @NotNull AGCCompatibilityScope scope, final @Nullable NamespacedKey key) {
        AGC.scheduler().ensurePrimaryThread("compatibility.revoke");
        this.declarations.remove(id(plugin.getName(), scope, key));
    }

    public synchronized @NotNull Collection<AGCCompatibilityDeclaration> declarations() {
        return Collections.unmodifiableCollection(new ArrayList<>(this.declarations.values()));
    }

    public synchronized boolean has(
        final @NotNull Plugin plugin,
        final @NotNull AGCCompatibilityScope scope,
        final @Nullable NamespacedKey key,
        final @NotNull AGCCompatibilityContract contract
    ) {
        final AGCCompatibilityDeclaration declaration = this.declarations.get(id(plugin.getName(), scope, key));
        return declaration != null && declaration.has(contract);
    }

    public synchronized int declarationCount() {
        return this.declarations.size();
    }

    private static String id(final String plugin, final AGCCompatibilityScope scope, final @Nullable NamespacedKey key) {
        return plugin + '|' + scope.name() + '|' + (key == null ? "*" : key.asString());
    }
}
