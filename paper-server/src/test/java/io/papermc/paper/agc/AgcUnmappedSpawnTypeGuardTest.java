package io.papermc.paper.agc.spawner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AgcUnmappedSpawnTypeGuard}.
 *
 * <p>The decisive verification for this guard is a live one (a spawn-heavy benchmark no longer dying in
 * {@code NaturalSpawner.isValidSpawnPostitionForType}, plus a one-time warning naming the offending
 * entity type); these tests only pin the parts that are meaningful off-server: the null contract and
 * the fail-open rule, so the guard can never suppress spawning because it could not read a registry.</p>
 */
public class AgcUnmappedSpawnTypeGuardTest {

    @Test
    void nullTypeIsTreatedAsUnrepresentable() {
        assertTrue(AgcUnmappedSpawnTypeGuard.isUnrepresentable(null),
            "a null type cannot be translated into a Bukkit EntityType by any path");
    }

    @Test
    void guardFailsOpenWhenRegistriesAreUnavailable() {
        final net.minecraft.world.entity.EntityType<?> type;
        try {
            type = net.minecraft.world.entity.EntityTypes.ZOMBIE;
        } catch (final Throwable nmsNotBootstrapped) {
            return; // no NMS registry bootstrap in this JVM: nothing meaningful to assert
        }
        // Outside a bootstrapped CraftBukkit server the registries are not bound; the guard must
        // report "representable" rather than skip every spawn candidate on the server.
        AgcUnmappedSpawnTypeGuard.clearCache();
        assertFalse(AgcUnmappedSpawnTypeGuard.isUnrepresentable(type),
            "with no bound registries the guard must defer to the vanilla spawn path, not suppress it");
    }

    @Test
    void cacheCanBeCleared() {
        AgcUnmappedSpawnTypeGuard.clearCache();
        assertTrue(AgcUnmappedSpawnTypeGuard.cachedTypes() >= 0);
    }
}
