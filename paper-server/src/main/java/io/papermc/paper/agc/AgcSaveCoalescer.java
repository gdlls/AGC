package io.papermc.paper.agc;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — single-writer save coalescer.
 *
 * <p>A dirty-bit + one-in-flight-writer primitive for expensive "snapshot and persist" work that
 * many actors request at once. The user-name cache is the motivating case: every player join
 * calls {@code CachedUserNameToIdResolver.add(...)} → {@code save(true)}, and each of those calls
 * snapshots (copies + sorts) the whole profile cache on the <b>calling</b> thread before handing
 * the serialization to an async executor. A 200 player join burst therefore did 200 full sorts
 * on the server thread and 200 JSON serializations of the same file.</p>
 *
 * <p>Semantics: any number of concurrent {@link #request()} calls collapse into one writer that
 * runs the action once, plus at most one trailing re-run to cover a request that arrived while the
 * writer was finishing. The action is <b>never</b> executed concurrently with itself, and after the
 * last request returns the action has been run at least once more with the up-to-date state, so no
 * update is ever silently dropped.</p>
 *
 * <p>Typical use:</p>
 * <pre>{@code
 * if (!COALESCER.request()) {
 *     return;                      // coalesced: the active writer will flush this request too
 * }
 * try {
 *     while (COALESCER.consume()) {   // consumes one pending request per iteration
 *         writeSnapshot();            // may be called once, or twice under a burst
 *     }
 * } finally {
 *     COALESCER.release();            // hands the writer role to a late request if one is pending
 * }
 * }</pre>
 *
 * <p>If a caller claims the writer and then fails to call {@link #release()} the coalescer stays
 * claimed forever (further requests are dropped), so the claim must always be released from a
 * {@code finally} block.</p>
 *
 * <p>Thread safety: all state is atomic; nothing here blocks. The class is a plain counter +
 * boolean state machine, so it is fully deterministic in tests.</p>
 */
public final class AgcSaveCoalescer {

    private final AtomicBoolean writerActive = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /**
     * Registers a request to run the guarded action.
     *
     * @return {@code true} if the caller now owns the writer role and must run the
     *         {@code consume()} / {@code release()} loop; {@code false} if an active writer will
     *         cover this request.
     */
    public boolean request() {
        this.requests.incrementAndGet();
        this.dirty.set(true);
        if (this.writerActive.compareAndSet(false, true)) {
            return true;
        }
        this.rejected.incrementAndGet();
        return false;
    }

    /**
     * Consumes one pending request. The writer loop calls this before each action execution.
     *
     * @return {@code true} if there is work to do (one action execution)
     */
    public boolean consume() {
        if (!this.dirty.compareAndSet(true, false)) {
            return false;
        }
        this.writes.incrementAndGet();
        return true;
    }

    /**
     * Releases the writer role. If a request arrived while the writer was finishing, ownership is
     * retained (returns {@code true}) so the caller must keep looping; otherwise the role is free
     * again (returns {@code false}) and any later request starts a new writer.
     *
     * @return whether the calling thread still owns the writer role
     */
    public boolean release() {
        this.writerActive.set(false);
        return this.dirty.get() && this.writerActive.compareAndSet(false, true);
    }

    /**
     * Convenience driver: runs {@code action} until no request is pending, then releases.
     * Only call this after {@link #request()} returned {@code true}.
     *
     * @return the number of times {@code action} ran
     */
    public int drain(final Runnable action) {
        int performed = 0;
        boolean owned = true;
        while (owned) {
            try {
                while (this.consume()) {
                    action.run();
                    performed++;
                }
            } finally {
                owned = this.release();
            }
        }
        return performed;
    }

    /** Total {@link #request()} calls. */
    public long requests() {
        return this.requests.get();
    }

    /** Total action executions (the useful work actually performed). */
    public long writes() {
        return this.writes.get();
    }

    /** Requests that were coalesced into an already active writer. */
    public long rejected() {
        return this.rejected.get();
    }

    /** True while a writer owns the role (test/diagnostic hook). */
    public boolean isWriterActive() {
        return this.writerActive.get();
    }

    /** True if a request is pending but not yet consumed (test/diagnostic hook). */
    public boolean hasPendingRequest() {
        return this.dirty.get();
    }

    public void reset() {
        this.writerActive.set(false);
        this.dirty.set(false);
        this.requests.set(0L);
        this.writes.set(0L);
        this.rejected.set(0L);
    }
}
