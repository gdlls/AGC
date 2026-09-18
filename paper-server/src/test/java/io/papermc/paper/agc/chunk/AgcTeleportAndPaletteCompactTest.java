package io.papermc.paper.agc.chunk;

import io.papermc.paper.agc.AgcCapabilityMatrix;
import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.core.IdMap;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcTeleportAndPaletteCompactTest {

    @Test
    public void testPaletteCompactionToZeroBits() {
        final List<String> list = new ArrayList<>();
        final Map<String, Integer> map = new HashMap<>();
        final IdMap<String> idMap = new IdMap<>() {
            @Override
            public int getId(String value) {
                return map.computeIfAbsent(value, k -> {
                    int id = list.size();
                    list.add(k);
                    return id;
                });
            }

            @Override
            public String byId(int id) {
                return (id >= 0 && id < list.size()) ? list.get(id) : null;
            }

            @Override
            public String byIdOrThrow(int id) {
                String val = byId(id);
                if (val == null) throw new IllegalArgumentException("No value for id " + id);
                return val;
            }

            @Override
            public int size() {
                return list.size();
            }

            @Override
            public java.util.Iterator<String> iterator() {
                return list.iterator();
            }
        };

        final Strategy<String> strategy = Strategy.createForBlockStates(idMap);
        final PalettedContainer<String> container = new PalettedContainer<>("air", strategy, null);

        // Initially 0 bits
        assertEquals(0, container.bitsPerEntry());
        assertInstanceOf(ZeroBitStorage.class, container.data.storage());
        assertEquals("air", container.get(0, 0, 0));

        // Setting a different value expands to 4 bits
        container.set(0, 0, 0, "stone");
        assertTrue(container.bitsPerEntry() >= 4);
        assertEquals("stone", container.get(0, 0, 0));

        // Compact back to air single value
        container.agc$resetToSingleValue("air");
        assertEquals(0, container.bitsPerEntry(), "Compacting to single value must reset bit storage to 0 bits");
        assertInstanceOf(ZeroBitStorage.class, container.data.storage());
        assertEquals("air", container.get(0, 0, 0));
        assertEquals("air", container.get(5, 5, 5));
    }

    @Test
    public void testWorldChunkUnloadDelayDuration() {
        final io.papermc.paper.configuration.type.Duration delay =
            io.papermc.paper.configuration.type.Duration.of("1s");
        assertEquals(20L, delay.ticks(), "1s unload delay must equal 20 ticks for 500 CCU memory protection");
    }

    @Test
    public void testBaselineOptimizationsAreEnabledByDefault() {
        final AgcCapabilityMatrix.Mode prev = AgcCapabilityMatrix.getMode();
        try {
            AgcCapabilityMatrix.setMode(AgcCapabilityMatrix.Mode.AGC_BASELINE);
            assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.CHUNK_PACKET_CACHE),
                "CHUNK_PACKET_CACHE must be active in baseline");
            assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.SPAWNER_DENSITY_OPTIMIZER),
                "SPAWNER_DENSITY_OPTIMIZER must be active in baseline");
            assertTrue(AgcCapabilityMatrix.isEnabled(AgcCapabilityMatrix.Feature.PARALLEL_LIGHT_ENGINE),
                "PARALLEL_LIGHT_ENGINE must be active in baseline");
        } finally {
            AgcCapabilityMatrix.setMode(prev);
        }
    }
}
