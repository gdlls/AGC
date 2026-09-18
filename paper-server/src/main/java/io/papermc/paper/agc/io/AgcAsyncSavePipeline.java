package io.papermc.paper.agc.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Asynchronous World Save & Chunk Flush Pipeline.
 *
 * <p>Completely decouples world saving, incremental autosaving, and disk I/O commitments
 * from the main tick loop using Copy-On-Write (COW) chunk snapshots and save request coalescing.</p>
 */
public final class AgcAsyncSavePipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcAsyncSavePipeline.class);
    private static final AgcAsyncSavePipeline INSTANCE = new AgcAsyncSavePipeline();

    public static final long SAVE_COALESCE_WINDOW_MS = 30_000L; // 30s coalescing

    private final ConcurrentHashMap<String, SaveTaskEntry> pendingSaves = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor;

    private final AtomicLong chunksQueued = new AtomicLong();
    private final AtomicLong chunksSavedAsync = new AtomicLong();
    private final AtomicLong savesCoalesced = new AtomicLong();

    public static AgcAsyncSavePipeline get() {
        return INSTANCE;
    }

    private AgcAsyncSavePipeline() {
        final ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(1);
            @Override
            public Thread newThread(final Runnable r) {
                final Thread t = new Thread(r, "agc-async-save-worker-" + this.count.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        };
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            factory
        );
        pool.prestartAllCoreThreads();
        this.ioExecutor = pool;
    }

    /**
     * Submits a chunk save task to the asynchronous I/O pipeline with automatic coalescing.
     *
     * @param worldKey World identifier
     * @param chunkKey 64-bit chunk coordinate key
     * @param snapshotData Serialized chunk snapshot payload
     * @param saveAction Direct disk commit runnable
     * @return true if queued as new task, false if coalesced into existing pending save
     */
    public boolean queueChunkSave(
        final String worldKey,
        final long chunkKey,
        final byte[] snapshotData,
        final Runnable saveAction
    ) {
        if (worldKey == null || saveAction == null) return false;
        final String saveId = worldKey + "#" + chunkKey;
        final long now = System.currentTimeMillis();

        final SaveTaskEntry entry = this.pendingSaves.compute(saveId, (k, existing) -> {
            if (existing != null && (now - existing.timestamp) < SAVE_COALESCE_WINDOW_MS) {
                // Coalesce: Update payload and action with newest snapshot safely under lock
                synchronized (existing) {
                    existing.snapshotData = snapshotData;
                    existing.saveAction = saveAction;
                    existing.timestamp = now;
                }
                this.savesCoalesced.incrementAndGet();
                return existing;
            }

            final SaveTaskEntry newEntry = new SaveTaskEntry(saveId, worldKey, chunkKey, snapshotData, saveAction, now);
            this.chunksQueued.incrementAndGet();
            this.ioExecutor.submit(() -> processSaveTask(newEntry));
            return newEntry;
        });

        return entry != null;
    }

    private void processSaveTask(final SaveTaskEntry entry) {
        while (true) {
            final Runnable action;
            synchronized (entry) {
                action = entry.saveAction;
                entry.saveAction = null;
            }
            if (action != null) {
                try {
                    action.run();
                } catch (final Throwable t) {
                    LOGGER.error("[AGC Async Save] Error executing async disk commit for {}: {}",
                        entry.saveId, t.getMessage(), t);
                }
            }
            synchronized (entry) {
                if (entry.saveAction == null) {
                    this.pendingSaves.remove(entry.saveId, entry);
                    this.chunksSavedAsync.incrementAndGet();
                    break;
                }
            }
        }
    }

    public void clear() {
        this.pendingSaves.clear();
        this.chunksQueued.set(0);
        this.chunksSavedAsync.set(0);
        this.savesCoalesced.set(0);
    }

    public AsyncSaveMetrics metrics() {
        return new AsyncSaveMetrics(
            this.pendingSaves.size(),
            this.chunksQueued.get(),
            this.chunksSavedAsync.get(),
            this.savesCoalesced.get()
        );
    }

    private static final class SaveTaskEntry {
        final String saveId;
        final String worldKey;
        final long chunkKey;
        volatile byte[] snapshotData;
        volatile Runnable saveAction;
        volatile long timestamp;

        SaveTaskEntry(final String saveId, final String worldKey, final long chunkKey,
                      final byte[] snapshotData, final Runnable saveAction, final long timestamp) {
            this.saveId = saveId;
            this.worldKey = worldKey;
            this.chunkKey = chunkKey;
            this.snapshotData = snapshotData;
            this.saveAction = saveAction;
            this.timestamp = timestamp;
        }
    }

    public record AsyncSaveMetrics(
        int pendingSaves,
        long chunksQueued,
        long chunksSavedAsync,
        long savesCoalesced
    ) {
    }
}
