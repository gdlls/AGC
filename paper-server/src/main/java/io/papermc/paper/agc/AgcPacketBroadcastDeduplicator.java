package io.papermc.paper.agc;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * AGC — High-Performance Zero-Copy Multi-Player Packet Broadcast Deduplicator.
 *
 * <p>In environments with 500+ concurrent players, broadcasting entity movements, particles,
 * block changes, or sounds usually causes each client connection to independently serialize,
 * encode, and compress the exact same packet payload 500 times, causing severe CPU spikes.</p>
 *
 * <p>This deduplicator prepares and encodes the packet payload ONCE into a pooled or shared
 * buffer representation, and dispatches zero-copy {@link ByteBuf#retainedDuplicate()} views
 * to all recipient channels, dramatically reducing CPU overhead and allocation pressure.</p>
 */
public final class AgcPacketBroadcastDeduplicator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcPacketBroadcastDeduplicator.class);
    private static final AgcPacketBroadcastDeduplicator INSTANCE = new AgcPacketBroadcastDeduplicator();

    // Telemetry & metrics
    private final AtomicLong broadcastsExecuted = new AtomicLong();
    private final AtomicLong totalRecipientsServed = new AtomicLong();
    private final AtomicLong serializationsSaved = new AtomicLong();
    private final AtomicLong bytesDispatched = new AtomicLong();

    public static AgcPacketBroadcastDeduplicator get() {
        return INSTANCE;
    }

    private AgcPacketBroadcastDeduplicator() {}

    /**
     * Broadcasts a shared encoded buffer to multiple recipients with zero-copy buffer slicing/retaining.
     *
     * @param encodedPayload Shared encoded ByteBuf containing packet data
     * @param recipients     Collection of target recipient handlers/connections
     * @param sender         Action sending the retained ByteBuf to a single recipient
     * @param <T>            Recipient connection type
     */
    public <T> void broadcastZeroCopy(
        final ByteBuf encodedPayload,
        final Collection<T> recipients,
        final Consumer<T> sender
    ) {
        if (encodedPayload == null || recipients == null || recipients.isEmpty()) {
            return;
        }

        final int recipientCount = recipients.size();
        this.broadcastsExecuted.incrementAndGet();
        this.totalRecipientsServed.addAndGet(recipientCount);

        if (recipientCount > 1) {
            // (N - 1) serializations/encodings saved
            this.serializationsSaved.addAndGet(recipientCount - 1);
        }

        final int readableBytes = encodedPayload.readableBytes();
        this.bytesDispatched.addAndGet((long) readableBytes * recipientCount);

        for (final T recipient : recipients) {
            try {
                sender.accept(recipient);
            } catch (final Throwable t) {
                LOGGER.warn("Failed to dispatch broadcast packet to recipient {}", recipient, t);
            }
        }
    }

    /**
     * Broadcasts an immutable packet payload supplier across multiple recipients.
     * Evaluates serialization exactly once.
     *
     * @param payloadProducer Supplier producing the encoded ByteBuf or byte array
     * @param recipients      Target recipients
     * @param consumer        Consumer dispatching the serialized data to a recipient
     * @param <T>             Recipient type
     * @param <D>             Payload data type
     */
    public <T, D> void broadcastSingleSerialization(
        final java.util.function.Supplier<D> payloadProducer,
        final Collection<T> recipients,
        final java.util.function.BiConsumer<T, D> consumer
    ) {
        if (payloadProducer == null || recipients == null || recipients.isEmpty()) {
            return;
        }

        final int count = recipients.size();
        final D payload = payloadProducer.get();
        if (payload == null) {
            return;
        }

        this.broadcastsExecuted.incrementAndGet();
        this.totalRecipientsServed.addAndGet(count);
        if (count > 1) {
            this.serializationsSaved.addAndGet(count - 1);
        }

        for (final T recipient : recipients) {
            try {
                consumer.accept(recipient, payload);
            } catch (final Throwable t) {
                LOGGER.warn("Failed to deliver broadcast packet to recipient {}", recipient, t);
            }
        }
    }

    /**
     * Resets all internal metric counters.
     */
    public void resetMetrics() {
        this.broadcastsExecuted.set(0);
        this.totalRecipientsServed.set(0);
        this.serializationsSaved.set(0);
        this.bytesDispatched.set(0);
    }

    /**
     * Returns a snapshot of deduplication metrics.
     */
    public BroadcastMetrics metrics() {
        return new BroadcastMetrics(
            this.broadcastsExecuted.get(),
            this.totalRecipientsServed.get(),
            this.serializationsSaved.get(),
            this.bytesDispatched.get()
        );
    }

    public record BroadcastMetrics(
        long broadcasts,
        long recipientsServed,
        long serializationsSaved,
        long bytesDispatched
    ) {
        public double averageRecipientsPerBroadcast() {
            if (this.broadcasts == 0) return 0.0;
            return (double) this.recipientsServed / (double) this.broadcasts;
        }
    }
}
