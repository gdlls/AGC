package io.papermc.paper.agc.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Ordered single-writer file commit queue for deferred player-adjacent saves.
 *
 * <p>Why this exists: stats / advancement / player-data saves were historically
 * fanned out to a shared 2-thread pool. Two rapid saves of the <i>same</i> file
 * could then commit out of order (older bytes winning), and a shutdown could exit
 * before in-flight writes landed. Both are silent data-loss vectors.</p>
 *
 * <p>Guarantees:</p>
 * <ul>
 *   <li>Single daemon writer thread: submissions execute in FIFO order, so a newer
 *       snapshot of a file can never be overwritten by an older one.</li>
 *   <li>Fail-open: a throwing task is logged and skipped; the queue keeps flowing.</li>
 *   <li>{@link #drain(long, TimeUnit)} lets server shutdown await quiescence with a
 *       bounded timeout instead of abandoning in-flight writes.</li>
 *   <li>Snapshots must be fully materialized <i>before</i> submission (JSON text,
 *       NBT tag copies). Tasks must never touch live mutable world state.</li>
 * </ul>
 */
public final class AgcOrderedFileWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcOrderedFileWriter.class);
    private static final AgcOrderedFileWriter INSTANCE = new AgcOrderedFileWriter();

    public static AgcOrderedFileWriter get() {
        return INSTANCE;
    }

    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread writerThread;

    private AgcOrderedFileWriter() {
        final Thread t = new Thread(this::pump, "agc-ordered-file-writer");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        this.writerThread = t;
        t.start();
    }

    private void pump() {
        while (this.running.get() || !this.queue.isEmpty()) {
            final Runnable task;
            try {
                task = this.queue.poll(100L, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            if (task == null) {
                continue;
            }
            this.inFlight.incrementAndGet();
            try {
                task.run();
                this.completed.incrementAndGet();
            } catch (final Throwable th) {
                this.failed.incrementAndGet();
                LOGGER.error("[AGC OrderedFileWriter] deferred file commit failed (queue keeps flowing)", th);
            } finally {
                this.inFlight.decrementAndGet();
            }
        }
    }

    /**
     * Submits an already-materialized file commit. Never blocks the caller
     * (unbounded queue; individual commits are small player-adjacent files).
     */
    public void submit(final Runnable commit) {
        if (commit == null) {
            return;
        }
        this.submitted.incrementAndGet();
        this.queue.add(commit);
    }

    /** Number of commits still waiting or executing. */
    public int pending() {
        return this.queue.size() + this.inFlight.get();
    }

    /**
     * Waits until the queue is empty (or the timeout elapses). Intended for
     * server shutdown: bounded wait, fail-open on timeout.
     *
     * @return {@code true} if fully drained before the timeout
     */
    public boolean drain(final long timeout, final TimeUnit unit) {
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (this.pending() == 0) {
                return true;
            }
            try {
                Thread.sleep(5L);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        final int left = this.pending();
        if (left > 0) {
            LOGGER.warn("[AGC OrderedFileWriter] shutdown drain timed out with {} commits pending; continuing shutdown (fail-open)", left);
        }
        return left == 0;
    }

    public OrderedFileWriterMetrics metrics() {
        return new OrderedFileWriterMetrics(
            this.submitted.get(),
            this.completed.get(),
            this.failed.get(),
            this.pending()
        );
    }

    public record OrderedFileWriterMetrics(long submitted, long completed, long failed, int pending) {}

    /** Test/maintenance hook: queue lengths only, does not stop the writer. */
    public void resetMetrics() {
        this.submitted.set(0);
        this.completed.set(0);
        this.failed.set(0);
    }
}
