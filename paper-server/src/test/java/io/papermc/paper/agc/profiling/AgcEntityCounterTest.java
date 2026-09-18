package io.papermc.paper.agc.profiling;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcEntityCounter}.
 */
public class AgcEntityCounterTest {

    @Test
    void verifiesEntityWeightCalculations() {
        // High complexity entities
        assertTrue(AgcEntityCounter.getEntityWeight(EntityType.VILLAGER) >= 4.0);
        assertTrue(AgcEntityCounter.getEntityWeight(EntityType.PILLAGER) >= 4.0);

        // Medium complexity monsters
        assertTrue(AgcEntityCounter.getEntityWeight(EntityType.ZOMBIE) >= 2.0);
        assertTrue(AgcEntityCounter.getEntityWeight(EntityType.SKELETON) >= 2.0);

        // Passives
        assertTrue(AgcEntityCounter.getEntityWeight(EntityType.COW) >= 1.2);

        // Projectiles and items
        assertTrue(AgcEntityCounter.getEntityWeight(EntityType.ITEM) < 1.0);
        assertTrue(AgcEntityCounter.getEntityWeight(EntityType.ARROW) < 1.0);

        // Trivial markers
        assertTrue(AgcEntityCounter.getEntityWeight(EntityType.ARMOR_STAND) <= 0.2);
        assertTrue(AgcEntityCounter.getEntityWeight(EntityType.MARKER) <= 0.1);
    }
}
