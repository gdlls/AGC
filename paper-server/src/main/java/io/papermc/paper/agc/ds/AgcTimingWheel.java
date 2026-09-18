package io.papermc.paper.agc.ds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Hierarchical Timing Wheel Scheduler.
 *
 * <p>Provides $O(1)$ constant-time insertion, cancellation, and execution of delayed tick tasks.
 * Replaces logarithmic $O(\log N)$ priority queues (like TreeSet/PriorityQueue) with a 3-tier
 * circular timing wheel structure:
 * <ul>
 *   <li><b>Tier 1 (Tick Wheel):</b> 64 slots, 1 tick resolution (0 - 3.2s)</li>
 *   <li><b>Tier 2 (Second Wheel):</b> 64 slots, 64 ticks resolution (3.2s - 204.8s)</li>
 *   <li><b>Tier 3 (Minute Wheel):</b> 64 slots, 4096 ticks resolution (~3.4 hours)</li>
 * </ul>
 * Overflow tasks are cascaded into lower tiers as time advances.</p>
 */
public final class AgcTimingWheel {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcTimingWheel.class);
    private static final AgcTimingWheel INSTANCE = new AgcTimingWheel();

    public static final int WHEEL_SIZE = 64;
    public static final int MASK = WHEEL_SIZE - 1;

    public static final long TIER1_RES = 1L;
    public static final long TIER2_RES = WHEEL_SIZE * TIER1_RES; // 64 ticks
    public static final long TIER3_RES = WHEEL_SIZE * TIER2_RES; // 4096 ticks

    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<WheelTask>[] tier1 = new ConcurrentLinkedQueue[WHEEL_SIZE];
    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<WheelTask>[] tier2 = new ConcurrentLinkedQueue[WHEEL_SIZE];
    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<WheelTask>[] tier3 = new ConcurrentLinkedQueue[WHEEL_SIZE];

    private final AtomicLong currentTick = new AtomicLong(0);

    private final AtomicLong tasksScheduled = new AtomicLong();
    private final AtomicLong tasksExecuted = new AtomicLong();
    private final AtomicLong tasksCancelled = new AtomicLong();
    private final AtomicLong cascadesPerformed = new AtomicLong();

    public static AgcTimingWheel get() {
        return INSTANCE;
    }

    public AgcTimingWheel() {
        for (int i = 0; i < WHEEL_SIZE; i++) {
            this.tier1[i] = new ConcurrentLinkedQueue<>();
            this.tier2[i] = new ConcurrentLinkedQueue<>();
            this.tier3[i] = new ConcurrentLinkedQueue<>();
        }
    }

    /**
     * Schedules a runnable task to be executed after a given tick delay.
     *
     * @param delayTicks Delay in server ticks (>= 0)
     * @param action Task action to execute
     * @return A {@link ScheduledTaskHandle} that can be used to cancel the task
     */
    public ScheduledTaskHandle schedule(final long delayTicks, final Runnable action) {
        if (action == null) return null;
        final long targetTick = this.currentTick.get() + Math.max(1L, delayTicks);
        final WheelTask task = new WheelTask(targetTick, action);
        insert(task);
        this.tasksScheduled.incrementAndGet();
        return task;
    }

    private void insert(final WheelTask task) {
        if (task.isCancelled()) return;

        final long remaining = task.targetTick - this.currentTick.get();
        if (remaining < TIER2_RES) {
            final int slot = (int) (task.targetTick & MASK);
            this.tier1[slot].offer(task);
        } else if (remaining < TIER3_RES) {
            final int slot = (int) ((task.targetTick / TIER2_RES) & MASK);
            this.tier2[slot].offer(task);
        } else {
            final int slot = (int) ((task.targetTick / TIER3_RES) & MASK);
            this.tier3[slot].offer(task);
        }
    }

    /**
     * Advances the timing wheel by one tick and executes all due tasks.
     *
     * @return Number of tasks executed in this tick
     */
    public int advanceTick() {
        final long tick = this.currentTick.incrementAndGet();
        int executed = 0;

        // Cascade from Tier 2 to Tier 1 when slot wraps
        if ((tick & MASK) == 0) {
            final int t2Slot = (int) ((tick / TIER2_RES) & MASK);
            final var t2Queue = this.tier2[t2Slot];
            WheelTask task;
            while ((task = t2Queue.poll()) != null) {
                if (!task.isCancelled()) {
                    insert(task);
                    this.cascadesPerformed.incrementAndGet();
                }
            }

            // Cascade from Tier 3 to Tier 2
            if (t2Slot == 0) {
                final int t3Slot = (int) ((tick / TIER3_RES) & MASK);
                final var t3Queue = this.tier3[t3Slot];
                while ((task = t3Queue.poll()) != null) {
                    if (!task.isCancelled()) {
                        insert(task);
                        this.cascadesPerformed.incrementAndGet();
                    }
                }
            }
        }

        final int t1Slot = (int) (tick & MASK);
        final var t1Queue = this.tier1[t1Slot];
        WheelTask task;
        final List<WheelTask> reinsertList = new ArrayList<>();

        while ((task = t1Queue.poll()) != null) {
            if (task.isCancelled()) {
                continue;
            }
            if (task.targetTick <= tick) {
                try {
                    task.action.run();
                    executed++;
                    this.tasksExecuted.incrementAndGet();
                } catch (final Throwable t) {
                    LOGGER.error("[AGC Timing Wheel] Error executing scheduled task: {}", t.getMessage(), t);
                }
            } else {
                reinsertList.add(task);
            }
        }

        for (final WheelTask reinsert : reinsertList) {
            insert(reinsert);
        }

        return executed;
    }

    public void clear() {
        for (int i = 0; i < WHEEL_SIZE; i++) {
            this.tier1[i].clear();
            this.tier2[i].clear();
            this.tier3[i].clear();
        }
        this.currentTick.set(0);
        this.tasksScheduled.set(0);
        this.tasksExecuted.set(0);
        this.tasksCancelled.set(0);
        this.cascadesPerformed.set(0);
    }

    public TimingWheelMetrics metrics() {
        int pending = 0;
        for (int i = 0; i < WHEEL_SIZE; i++) {
            pending += this.tier1[i].size() + this.tier2[i].size() + this.tier3[i].size();
        }
        return new TimingWheelMetrics(
            this.currentTick.get(),
            pending,
            this.tasksScheduled.get(),
            this.tasksExecuted.get(),
            this.tasksCancelled.get(),
            this.cascadesPerformed.get()
        );
    }

    public interface ScheduledTaskHandle {
        boolean cancel();
        boolean isCancelled();
        long getTargetTick();
    }

    private final class WheelTask implements ScheduledTaskHandle {
        private final long targetTick;
        private final Runnable action;
        private volatile boolean cancelled = false;

        private WheelTask(final long targetTick, final Runnable action) {
            this.targetTick = targetTick;
            this.action = action;
        }

        @Override
        public boolean cancel() {
            if (!this.cancelled) {
                this.cancelled = true;
                AgcTimingWheel.this.tasksCancelled.incrementAndGet();
                return true;
            }
            return false;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public long getTargetTick() {
            return this.targetTick;
        }
    }

    public record TimingWheelMetrics(
        long currentTick,
        int pendingTasks,
        long tasksScheduled,
        long tasksExecuted,
        long tasksCancelled,
        long cascadesPerformed
    ) {
    }
}
