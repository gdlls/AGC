package io.papermc.paper.agc.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * AGC — Chat & Text Component JSON Serialization Broadcast Optimizer.
 *
 * <p>Eliminates massive CPU serialization waste when broadcasting server announcements,
 * bossbar updates, actionbars, and chat messages to thousands of connected players:
 * <ul>
 *   <li><b>Single-Pass JSON Broadcast:</b> Serializes the text component into JSON exactly once, then dispatches the pre-serialized payload to all $N$ recipients.</li>
 *   <li><b>Component Cache:</b> Interns frequently formatted text components.</li>
 * </ul>
 * </p>
 */
public final class AgcChatOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcChatOptimizer.class);
    private static final AgcChatOptimizer INSTANCE = new AgcChatOptimizer();

    private final ConcurrentHashMap<String, String> componentJsonCache = new ConcurrentHashMap<>();

    private final AtomicLong broadcastsProcessed = new AtomicLong();
    private final AtomicLong jsonSerializationsSaved = new AtomicLong();
    private final AtomicLong componentCacheHits = new AtomicLong();

    public static AgcChatOptimizer get() {
        return INSTANCE;
    }

    private AgcChatOptimizer() {}

    /**
     * Broadcasts a text component to multiple player targets by serializing to JSON exactly once.
     *
     * @param component Text component object
     * @param jsonSerializer Function converting component to serialized JSON string
     * @param recipients Target player collection
     * @param packetSender Consumer sending the pre-serialized packet/JSON to each player
     * @param <C> Component type
     * @param <P> Player type
     */
    public <C, P> void broadcastSinglePass(
        final C component,
        final Function<C, String> jsonSerializer,
        final Collection<P> recipients,
        final java.util.function.BiConsumer<P, String> packetSender
    ) {
        if (component == null || recipients == null || recipients.isEmpty() || packetSender == null) return;
        this.broadcastsProcessed.incrementAndGet();

        final String json = jsonSerializer != null ? jsonSerializer.apply(component) : "";
        final int count = recipients.size();

        if (count > 1) {
            this.jsonSerializationsSaved.addAndGet(count - 1);
        }

        for (final P player : recipients) {
            packetSender.accept(player, json);
        }
    }

    /**
     * Retrieves or caches JSON representation of a static component string.
     */
    public String getOrCreateCachedJson(final String rawText, final Function<String, String> serializer) {
        if (rawText == null) return "";
        final String cached = this.componentJsonCache.get(rawText);
        if (cached != null) {
            this.componentCacheHits.incrementAndGet();
            return cached;
        }

        final String computed = serializer != null ? serializer.apply(rawText) : rawText;
        this.componentJsonCache.put(rawText, computed);
        return computed;
    }

    public void clear() {
        this.componentJsonCache.clear();
        this.broadcastsProcessed.set(0);
        this.jsonSerializationsSaved.set(0);
        this.componentCacheHits.set(0);
    }

    public ChatOptimizerMetrics metrics() {
        return new ChatOptimizerMetrics(
            this.componentJsonCache.size(),
            this.broadcastsProcessed.get(),
            this.jsonSerializationsSaved.get(),
            this.componentCacheHits.get()
        );
    }

    public record ChatOptimizerMetrics(
        int cachedComponents,
        long broadcastsProcessed,
        long jsonSerializationsSaved,
        long componentCacheHits
    ) {
    }
}
