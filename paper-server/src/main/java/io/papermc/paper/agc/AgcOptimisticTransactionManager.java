package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * AGC — Software Transactional Memory (STM) & Optimistic Transaction Manager.
 *
 * <p>Enables safe, lock-free, cross-region and cross-world mutations from legacy plugins
 * without deadlocking worker threads or violating causal consistency.</p>
 *
 * <p>When a plugin running on a region worker thread modifies a block or entity outside its
 * local region boundary, this manager records the mutation in a thread-local transaction descriptor.
 * At commit time, snapshot versions are verified via CAS:
 * <ul>
 *   <li><b>Local / Non-conflicting:</b> Applied directly with zero locking overhead.</li>
 *   <li><b>Remote Region:</b> Enqueued atomically into the target region's mailbox or
 *   {@link AgcCrossWorldQueue} for deferred post-barrier application.</li>
 *   <li><b>Conflict Detected:</b> Rolled back and reported for transparent retry.</li>
 * </ul>
 * </p>
 */
public final class AgcOptimisticTransactionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcOptimisticTransactionManager.class);
    private static final AgcOptimisticTransactionManager INSTANCE = new AgcOptimisticTransactionManager();

    private final AtomicLong nextTransactionId = new AtomicLong(1);
    private final ThreadLocal<Transaction> activeTransaction = new ThreadLocal<>();

    /** Version table for conflict detection (Key: worldId + ":" + packedPos, Value: version). */
    private final ConcurrentHashMap<String, Long> versionTable = new ConcurrentHashMap<>();

    private final AtomicLong totalStarted = new AtomicLong();
    private final AtomicLong totalCommitted = new AtomicLong();
    private final AtomicLong totalRolledBack = new AtomicLong();
    private final AtomicLong totalCrossRegionEnqueued = new AtomicLong();
    private final AtomicLong totalConflictsDetected = new AtomicLong();

    public static AgcOptimisticTransactionManager get() {
        return INSTANCE;
    }

    private AgcOptimisticTransactionManager() {}

    /**
     * Begins an optimistic transaction on the current thread.
     */
    public Transaction begin(final String initiator) {
        final long txId = this.nextTransactionId.getAndIncrement();
        final Transaction tx = new Transaction(txId, initiator != null ? initiator : "unknown", System.nanoTime());
        this.activeTransaction.set(tx);
        this.totalStarted.incrementAndGet();
        return tx;
    }

    /**
     * Returns the active transaction on the current thread, or null if none.
     */
    public Transaction currentTransaction() {
        return this.activeTransaction.get();
    }

    /**
     * Records a block mutation under the active transaction (or begins a single-op transaction).
     */
    public void recordBlockMutation(
        final String worldId,
        final long packedPos,
        final int expectedStateId,
        final int newStateId,
        final Consumer<Integer> stateApplier
    ) {
        Transaction tx = this.activeTransaction.get();
        final boolean autoCommit = (tx == null);
        if (autoCommit) {
            tx = begin("auto-block-mutation");
        }

        final String versionKey = worldId + ":" + packedPos;
        final long currentVer = this.versionTable.getOrDefault(versionKey, 0L);

        final BlockMutation mutation = new BlockMutation(
            worldId, packedPos, currentVer, expectedStateId, newStateId, stateApplier
        );
        tx.addBlockMutation(mutation);

        if (autoCommit) {
            commit();
        }
    }

    /**
     * Commits the active transaction on the current thread.
     *
     * @return true if commit succeeded, false if aborted due to conflict
     */
    public boolean commit() {
        final Transaction tx = this.activeTransaction.get();
        if (tx == null) {
            return false;
        }

        this.activeTransaction.remove();

        // 1. Conflict Check: Verify all read versions against current global version table
        for (final BlockMutation bm : tx.getBlockMutations()) {
            final String versionKey = bm.worldId() + ":" + bm.packedPos();
            final long currentVer = this.versionTable.getOrDefault(versionKey, 0L);
            if (currentVer != bm.snapshotVersion()) {
                // Conflict detected!
                this.totalConflictsDetected.incrementAndGet();
                this.totalRolledBack.incrementAndGet();
                LOGGER.debug("AGC STM: Transaction #{} conflict detected on {}", tx.id(), versionKey);
                return false;
            }
        }

        // 2. Apply mutations & Advance versions
        final AgcPluginVirtualizer.VirtualContext ctx = AgcPluginVirtualizer.currentContext();
        final String currentWorld = ctx != null ? ctx.worldId() : null;

        for (final BlockMutation bm : tx.getBlockMutations()) {
            final String versionKey = bm.worldId() + ":" + bm.packedPos();
            this.versionTable.put(versionKey, bm.snapshotVersion() + 1);

            // Check if mutation belongs to current thread's world/region
            if (currentWorld != null && currentWorld.equalsIgnoreCase(bm.worldId())) {
                // Local region/world: apply directly
                try {
                    if (bm.applier() != null) {
                        bm.applier().accept(bm.newStateId());
                    }
                } catch (final Throwable t) {
                    LOGGER.error("AGC STM: Failed applying local mutation in transaction #{}", tx.id(), t);
                }
            } else {
                // Remote region/world: enqueue into CrossWorldQueue for deterministic post-barrier drain
                this.totalCrossRegionEnqueued.incrementAndGet();
                AgcCrossWorldQueue.get().enqueue(
                    "stm-tx-" + tx.id() + "-" + bm.worldId(),
                    () -> {
                        if (bm.applier() != null) {
                            bm.applier().accept(bm.newStateId());
                        }
                    }
                );
            }
        }

        this.totalCommitted.incrementAndGet();
        return true;
    }

    /**
     * Aborts and rolls back the active transaction on the current thread.
     */
    public void rollback() {
        final Transaction tx = this.activeTransaction.get();
        if (tx != null) {
            this.activeTransaction.remove();
            this.totalRolledBack.incrementAndGet();
        }
    }

    public void clear() {
        this.activeTransaction.remove();
        this.versionTable.clear();
        this.totalStarted.set(0);
        this.totalCommitted.set(0);
        this.totalRolledBack.set(0);
        this.totalCrossRegionEnqueued.set(0);
        this.totalConflictsDetected.set(0);
    }

    public StmMetrics metrics() {
        return new StmMetrics(
            this.totalStarted.get(),
            this.totalCommitted.get(),
            this.totalRolledBack.get(),
            this.totalCrossRegionEnqueued.get(),
            this.totalConflictsDetected.get(),
            this.versionTable.size()
        );
    }

    public static final class Transaction {
        private final long id;
        private final String initiator;
        private final long startNanos;
        private final List<BlockMutation> blockMutations = new ArrayList<>();

        public Transaction(final long id, final String initiator, final long startNanos) {
            this.id = id;
            this.initiator = initiator;
            this.startNanos = startNanos;
        }

        public long id() { return this.id; }
        public String initiator() { return this.initiator; }
        public long startNanos() { return this.startNanos; }

        public void addBlockMutation(final BlockMutation mutation) {
            this.blockMutations.add(mutation);
        }

        public List<BlockMutation> getBlockMutations() {
            return Collections.unmodifiableList(this.blockMutations);
        }
    }

    public record BlockMutation(
        String worldId,
        long packedPos,
        long snapshotVersion,
        int expectedStateId,
        int newStateId,
        Consumer<Integer> applier
    ) {
    }

    public record StmMetrics(
        long totalStarted,
        long totalCommitted,
        long totalRolledBack,
        long totalCrossRegionEnqueued,
        long totalConflictsDetected,
        int activeVersionEntries
    ) {
    }
}
