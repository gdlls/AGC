package io.papermc.paper.agc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — NUMA-Aware Work-Stealing Multi-Core Scheduler.
 *
 * <p>Optimized for 128-core / 256-thread AMD EPYC and multi-socket server processors.
 * Partitions worker threads into CCD (Core Complex Die) / NUMA node worker groups.
 * Workers execute tasks from their local node deque first (maximizing L1/L2/L3 CPU cache hits),
 * and only attempt cross-NUMA work-stealing when their local queue is exhausted.</p>
 */
public final class AgcNumaWorkStealingScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcNumaWorkStealingScheduler.class);
    private static final AgcNumaWorkStealingScheduler INSTANCE = new AgcNumaWorkStealingScheduler();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger threadIdCounter = new AtomicInteger();

    private int totalNodes = 1;
    private int workersPerNode = 1;
    private NumaWorker[][] workerNodes;

    private final AtomicLong tasksSubmitted = new AtomicLong();
    private final AtomicLong tasksExecutedLocal = new AtomicLong();
    private final AtomicLong tasksStolen = new AtomicLong();

    public static AgcNumaWorkStealingScheduler get() {
        return INSTANCE;
    }

    private AgcNumaWorkStealingScheduler() {}

    /**
     * Bootstraps the NUMA-aware work-stealing thread pool.
     *
     * @param totalWorkers Total worker threads (e.g. 16..128)
     * @param numaNodes    Number of NUMA nodes / CCD dies (e.g. 1..8)
     */
    public synchronized void bootstrap(final int totalWorkers, final int numaNodes) {
        if (!this.running.compareAndSet(false, true)) {
            return;
        }

        this.totalNodes = Math.max(1, numaNodes);
        this.workersPerNode = Math.max(1, totalWorkers / this.totalNodes);

        this.workerNodes = new NumaWorker[this.totalNodes][this.workersPerNode];

        // Construct the complete topology before starting any worker. A worker can attempt to
        // steal immediately after its thread starts; starting inside the construction loop let
        // the first workers observe null sibling/remote slots and die with an NPE before the
        // remaining topology had been published.
        for (int n = 0; n < this.totalNodes; n++) {
            for (int w = 0; w < this.workersPerNode; w++) {
                this.workerNodes[n][w] = new NumaWorker(n, w);
            }
        }
        for (int n = 0; n < this.totalNodes; n++) {
            for (int w = 0; w < this.workersPerNode; w++) {
                this.workerNodes[n][w].thread.start();
            }
        }

        LOGGER.info("AGC NUMA Work-Stealing Scheduler online: {} nodes x {} workers = {} total threads",
            this.totalNodes, this.workersPerNode, (this.totalNodes * this.workersPerNode));
    }

    /**
     * Shuts down all workers.
     */
    public synchronized void shutdown() {
        if (!this.running.compareAndSet(true, false)) {
            return;
        }

        if (this.workerNodes != null) {
            for (int n = 0; n < this.totalNodes; n++) {
                for (int w = 0; w < this.workersPerNode; w++) {
                    final NumaWorker worker = this.workerNodes[n][w];
                    if (worker != null) {
                        worker.active = false;
                        worker.thread.interrupt();
                    }
                }
            }
        }
    }

    /**
     * Submits a task with node affinity preference.
     *
     * @param task          Runnable to execute
     * @param preferredNode Preferred NUMA node index
     */
    public void submit(final Runnable task, final int preferredNode) {
        if (task == null || !this.running.get() || this.workerNodes == null) {
            if (task != null) {
                task.run();
            }
            return;
        }

        this.tasksSubmitted.incrementAndGet();
        final int targetNode = Math.abs(preferredNode) % this.totalNodes;
        // Distribute to first available worker in target node
        final NumaWorker worker = this.workerNodes[targetNode][0];
        worker.taskDeque.offer(task);
    }

    /**
     * Executes a batch of tasks synchronously with work-stealing barrier wait.
     */
    public void executeBatchSync(final List<Runnable> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        if (!this.running.get() || this.workerNodes == null) {
            for (final Runnable t : tasks) {
                t.run();
            }
            return;
        }

        final CountDownLatch latch = new CountDownLatch(tasks.size());

        for (int i = 0; i < tasks.size(); i++) {
            final Runnable task = tasks.get(i);
            final int node = i % this.totalNodes;
            submit(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            }, node);
        }

        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void clearMetrics() {
        this.tasksSubmitted.set(0);
        this.tasksExecutedLocal.set(0);
        this.tasksStolen.set(0);
    }

    public SchedulerMetrics metrics() {
        return new SchedulerMetrics(
            this.tasksSubmitted.get(),
            this.tasksExecutedLocal.get(),
            this.tasksStolen.get(),
            this.totalNodes,
            this.totalNodes * this.workersPerNode,
            this.running.get()
        );
    }

    private final class NumaWorker implements Runnable {
        private final int nodeId;
        private final int workerIndex;
        private final ConcurrentLinkedDeque<Runnable> taskDeque = new ConcurrentLinkedDeque<>();
        private final Thread thread;
        private volatile boolean active = true;

        public NumaWorker(final int nodeId, final int workerIndex) {
            this.nodeId = nodeId;
            this.workerIndex = workerIndex;
            this.thread = new Thread(this, "AGC-NUMA-Node" + nodeId + "-Worker" + workerIndex);
            this.thread.setDaemon(true);
        }

        @Override
        public void run() {
            while (this.active) {
                Runnable task = this.taskDeque.pollFirst();

                if (task != null) {
                    tasksExecutedLocal.incrementAndGet();
                    runSafe(task);
                    continue;
                }

                // Local queue empty -> Attempt intra-node steal first, then cross-node steal
                task = stealTask();
                if (task != null) {
                    tasksStolen.incrementAndGet();
                    runSafe(task);
                    continue;
                }

                // Nothing to execute -> brief exponential yield/sleep
                try {
                    Thread.sleep(1);
                } catch (final InterruptedException e) {
                    if (!this.active) {
                        break;
                    }
                }
            }
        }

        private Runnable stealTask() {
            if (workerNodes == null) {
                return null;
            }

            // 1. Intra-node steal
            for (int w = 0; w < workersPerNode; w++) {
                if (w != this.workerIndex) {
                    final NumaWorker sibling = workerNodes[this.nodeId][w];
                    final Runnable stolen = sibling.taskDeque.pollLast();
                    if (stolen != null) {
                        return stolen;
                    }
                }
            }

            // 2. Cross-node steal
            for (int n = 0; n < totalNodes; n++) {
                if (n != this.nodeId) {
                    for (int w = 0; w < workersPerNode; w++) {
                        final NumaWorker remote = workerNodes[n][w];
                        final Runnable stolen = remote.taskDeque.pollLast();
                        if (stolen != null) {
                            return stolen;
                        }
                    }
                }
            }

            return null;
        }

        private void runSafe(final Runnable task) {
            try {
                task.run();
            } catch (final Throwable t) {
                LOGGER.error("Uncaught exception in NUMA worker {}", this.thread.getName(), t);
            }
        }
    }

    public record SchedulerMetrics(
        long tasksSubmitted,
        long tasksExecutedLocal,
        long tasksStolen,
        int totalNodes,
        int totalWorkers,
        boolean running
    ) {
    }
}
