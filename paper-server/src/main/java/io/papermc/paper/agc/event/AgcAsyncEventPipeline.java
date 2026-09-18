package io.papermc.paper.agc.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * AGC — High-Throughput Asynchronous Event Pipeline.
 *
 * <p>Offloads non-mutating, read-only informational and logging events to dedicated worker threads,
 * freeing the main tick loop from synchronous plugin listener delays.</p>
 */
public final class AgcAsyncEventPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcAsyncEventPipeline.class);
    private static final AgcAsyncEventPipeline INSTANCE = new AgcAsyncEventPipeline();

    private final ExecutorService asyncWorkerPool;

    private final ConcurrentHashMap<Integer, Object> coalescedMoveEvents = new ConcurrentHashMap<>();

    private final AtomicLong asyncEventsSubmitted = new AtomicLong();
    private final AtomicLong asyncEventsExecuted = new AtomicLong();
    private final AtomicLong eventsCoalesced = new AtomicLong();

    public static AgcAsyncEventPipeline get() {
        return INSTANCE;
    }

    private AgcAsyncEventPipeline() {
        final int workers = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.asyncWorkerPool = new ThreadPoolExecutor(
            workers,
            workers,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16384),
            r -> {
                final Thread t = new Thread(r, "agc-async-event-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Submits a read-only event for asynchronous execution on the worker pool.
     */
    public <E> void dispatchAsync(final E event, final Consumer<E> listener) {
        if (event == null || listener == null) return;
        this.asyncEventsSubmitted.incrementAndGet();

        this.asyncWorkerPool.submit(() -> {
            try {
                listener.accept(event);
                this.asyncEventsExecuted.incrementAndGet();
            } catch (final Throwable t) {
                LOGGER.error("Uncaught exception in async event pipeline", t);
            }
        });
    }

    /**
     * Coalesces high-frequency movement events per entity to reduce redundant listener calls.
     *
     * @param entityId Entity ID
     * @param event Move event payload
     */
    public void coalesceMoveEvent(final int entityId, final Object event) {
        if (event == null) return;
        final Object prev = this.coalescedMoveEvents.put(entityId, event);
        if (prev != null) {
            this.eventsCoalesced.incrementAndGet();
        }
    }

    /**
     * Flushes and delivers all pending coalesced movement events.
     */
    public void flushCoalescedMoveEvents(final Consumer<Object> moveListener) {
        if (moveListener == null || this.coalescedMoveEvents.isEmpty()) return;

        for (final Integer entityId : this.coalescedMoveEvents.keySet()) {
            final Object event = this.coalescedMoveEvents.remove(entityId);
            if (event != null) {
                try {
                    moveListener.accept(event);
                } catch (final Throwable t) {
                    LOGGER.warn("Failed delivering coalesced move event for entity {}", entityId, t);
                }
            }
        }
    }

    public void clear() {
        this.coalescedMoveEvents.clear();
        this.asyncEventsSubmitted.set(0);
        this.asyncEventsExecuted.set(0);
        this.eventsCoalesced.set(0);
    }

    public PipelineMetrics metrics() {
        return new PipelineMetrics(
            this.coalescedMoveEvents.size(),
            this.asyncEventsSubmitted.get(),
            this.asyncEventsExecuted.get(),
            this.eventsCoalesced.get()
        );
    }

    public record PipelineMetrics(
        int pendingCoalescedEvents,
        long asyncEventsSubmitted,
        long asyncEventsExecuted,
        long eventsCoalesced
    ) {
    }
}
