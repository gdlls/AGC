package io.papermc.paper.agc.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcChatOptimizerTest {

    @BeforeEach
    public void setup() {
        AgcChatOptimizer.get().clear();
    }

    @Test
    public void testBroadcastSinglePass() {
        AgcChatOptimizer opt = AgcChatOptimizer.get();

        List<String> players = List.of("Player1", "Player2", "Player3", "Player4");
        AtomicInteger serializeCount = new AtomicInteger(0);
        List<String> received = new ArrayList<>();

        opt.broadcastSinglePass(
            "Hello World",
            text -> {
                serializeCount.incrementAndGet();
                return "{\"text\":\"" + text + "\"}";
            },
            players,
            (player, json) -> received.add(player + ":" + json)
        );

        assertEquals(1, serializeCount.get()); // Only 1 serialization for 4 players
        assertEquals(4, received.size());
        assertEquals(3, opt.metrics().jsonSerializationsSaved());
        assertEquals(1, opt.metrics().broadcastsProcessed());
    }

    @Test
    public void testComponentJsonCaching() {
        AgcChatOptimizer opt = AgcChatOptimizer.get();
        AtomicInteger serializeCount = new AtomicInteger(0);

        String json1 = opt.getOrCreateCachedJson("ServerRestarting", s -> {
            serializeCount.incrementAndGet();
            return "{\"msg\":\"" + s + "\"}";
        });
        assertEquals("{\"msg\":\"ServerRestarting\"}", json1);
        assertEquals(1, serializeCount.get());

        // Cache hit
        String json2 = opt.getOrCreateCachedJson("ServerRestarting", s -> {
            serializeCount.incrementAndGet();
            return "different";
        });
        assertEquals("{\"msg\":\"ServerRestarting\"}", json2);
        assertEquals(1, serializeCount.get());
        assertEquals(1, opt.metrics().componentCacheHits());
    }
}
