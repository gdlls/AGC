package io.papermc.paper.agc;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Folia regionized scheduler 위에 올라가는 두 번째 tick 가속기.
 *
 * <p>Paper/Folia의 regionized tick이 chunk region 단위로 멀티스레드 tick을 돌리는 동안,
 * AGC는 그 위에 <b>월드 단위 글로벌 비동기 워크로드 풀</b>을 추가합니다.</p>
 *
 * <p>용도:</p>
 * <ul>
 *   <li>월드별 비동기 chunk unload</li>
 *   <li>월드별 비동기 player packet flush</li>
 *   <li>월드 메타데이터 저장 (world.dat, level.dat 백업)</li>
 *   <li>Cross-region 비동기 작업 (월드 간 텔레포트, 인벤토리 동기화)</li>
 * </ul>
 *
 * <p>이 스레드 풀은 <b>region tick thread</b>와 분리되어 있어, region tick이 막혀도
 * 비동기 워크로드는 계속 진행됩니다.</p>
 *
 * <p><b>호환성:</b> 이 풀은 메인 tick / region tick과 별개의 executor를 사용하므로
 * Bukkit {@code SchedulerTask} 순서와 분리됩니다. 플러그인이 동기적으로 결과를
 * 기다리는 경우 {@code AsyncScheduler#runNow} 또는 메인 스레드 큐로 라우팅해야 합니다.</p>
 */
public final class AgcFoliaTuning {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcFoliaTuning.class);

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicInteger WORKER_ID = new AtomicInteger();
    private static volatile ScheduledExecutorService ASYNC_POOL;

    private AgcFoliaTuning() {}

    /**
     * 서버 부팅 시 1회 호출. 비동기 워크로드 풀 초기화.
     *
     * <p>멱등성: 두 번 이상 호출해도 두 번째부터는 no-op.</p>
     */
    public static void bootstrap() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        final int threads = AgcHotPathCache.suggestedGlobalRegionThreads();
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "AGC-Async-Worker-" + WORKER_ID.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, throwable) ->
                LOGGER.error("Uncaught exception in AGC async worker {}", thread.getName(), throwable));
            return t;
        };
        ASYNC_POOL = Executors.newScheduledThreadPool(threads, factory);
        LOGGER.info("AGC async workload pool started with {} threads", threads);

        // Bootstrap Parallel World Tick Engine
        AgcParallelWorldTickEngine.get().bootstrap();
    }

    /**
     * 비동기 풀에 작업을 제출. 예외는 풀의 uncaughtExceptionHandler로 라우팅되며
     * 호출자에게는 전파되지 않습니다.
     *
     * @throws RejectedExecutionException 풀 shutdown 이후 호출 시
     */
    public static void submitAsync(final Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        final ScheduledExecutorService pool = ASYNC_POOL;
        if (pool == null) {
            // 부트스트랩 전이면 동기 fallback. 테스트 환경에서 안전.
            task.run();
            return;
        }
        try {
            pool.execute(task);
        } catch (final RejectedExecutionException e) {
            // shutdown 진행 중 — 호출자가 결정하도록 그대로 전파.
            throw e;
        }
    }

    /**
     * 비동기 풀에 지연 작업을 예약.
     *
     * @return 취소 가능한 future. 부트스트랩 전이면 {@code null}.
     */
    public static ScheduledFuture<?> scheduleAsync(final Runnable task, final long delayMs) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (delayMs < 0L) {
            throw new IllegalArgumentException("delayMs must be >= 0");
        }
        final ScheduledExecutorService pool = ASYNC_POOL;
        if (pool == null) {
            // 부트스트랩 전이면 동기 fallback.
            task.run();
            return null;
        }
        return pool.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 서버 shutdown 시 호출. 진행 중인 작업이 끝날 때까지 짧게 대기 후 강제 종료.
     */
    public static void shutdown() {
        AgcParallelWorldTickEngine.get().shutdown();
        final ScheduledExecutorService pool = ASYNC_POOL;
        ASYNC_POOL = null;
        // STARTED를 false로 되돌려서 재시작 후 bootstrap()이 풀을 다시 만들 수 있게 한다.
        // (테스트 격리 / 핫 reload 시나리오 대응)
        STARTED.set(false);
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                final List<Runnable> dropped = pool.shutdownNow();
                LOGGER.warn("AGC async workload pool forced shutdown, dropped {} tasks", dropped.size());
            }
        } catch (final InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 풀 사이즈 / 부트스트랩 상태 / 살아있는 워커 수. 디버그 / spark / Timings 노출용.
     */
    public static PoolStatus status() {
        final ScheduledExecutorService pool = ASYNC_POOL;
        if (pool == null) {
            return new PoolStatus(false, 0, 0, 0);
        }
        if (pool instanceof final java.util.concurrent.ThreadPoolExecutor tpe) {
            return new PoolStatus(
                STARTED.get(),
                tpe.getCorePoolSize(),
                tpe.getActiveCount(),
                tpe.getQueue().size()
            );
        }
        return new PoolStatus(STARTED.get(), -1, -1, -1);
    }

    public record PoolStatus(boolean started, int coreSize, int activeThreads, int queueSize) {
    }

    // -----------------------------------------------------------------------
    // Per-world tick budget (Folia regionized threads 위에서 동작)
    // -----------------------------------------------------------------------

    private static final ConcurrentHashMap<ServerLevel, WorldTickBudget> BUDGETS = new ConcurrentHashMap<>();

    public static WorldTickBudget budgetFor(final ServerLevel level) {
        return BUDGETS.computeIfAbsent(level, l -> new WorldTickBudget());
    }

    /**
     * 모든 월드 budget을 한 번에 reset. 새 tick 시작 시 메인에서 호출.
     */
    public static void resetAllBudgets() {
        for (final WorldTickBudget budget : BUDGETS.values()) {
            budget.resetTickWindow();
        }
    }

    /**
     * shutdown 시 모든 budget 추적 해제.
     */
    public static void clearBudgets() {
        BUDGETS.clear();
    }

    /**
     * 월드 tick 시간 / 엔티티 수 / 청크 수 누적.
     *
     * <p>원래 tick 단위 메트릭을 lock-free로 누적. sliding window는 외부 구현에 맡기고
     * 이 클래스는 단순 누적만 담당한다.</p>
     */
    public static final class WorldTickBudget {
        private final AtomicLong totalTickNanos = new AtomicLong();
        private final AtomicLong totalEntitiesTicked = new AtomicLong();
        private final AtomicLong totalChunksTicked = new AtomicLong();
        private final AtomicLong tickCount = new AtomicLong();
        private final AtomicLong lastTickStartNanos = new AtomicLong(System.nanoTime());
        private final AtomicLong lastTickDurationNanos = new AtomicLong();

        public void recordTickStart() {
            this.lastTickStartNanos.set(System.nanoTime());
        }

        public void recordTickEnd(final int entities, final int chunks) {
            final long now = System.nanoTime();
            final long started = this.lastTickStartNanos.get();
            final long durationNanos = Math.max(0L, now - started);
            this.lastTickDurationNanos.set(durationNanos);
            this.totalTickNanos.addAndGet(durationNanos);
            this.totalEntitiesTicked.addAndGet(Math.max(0, entities));
            this.totalChunksTicked.addAndGet(Math.max(0, chunks));
            this.tickCount.incrementAndGet();
        }

        /**
         * 평균 tick 시간 (밀리초).
         *
         * <p>이전 구현은 {@code totalNanos / entities / 1000}이었는데 의미가
         * 부정확했다. tick 1회당 평균 시간을 직접 반환.</p>
         */
        public double averageTickMillis() {
            final long ticks = this.tickCount.get();
            if (ticks == 0L) {
                return 0.0;
            }
            return (this.totalTickNanos.get() / 1_000_000.0) / (double) ticks;
        }

        /**
         * 직전 tick 시간 (밀리초).
         */
        public double lastTickMillis() {
            return this.lastTickDurationNanos.get() / 1_000_000.0;
        }

        public long entitiesTicked() {
            return this.totalEntitiesTicked.get();
        }

        public long chunksTicked() {
            return this.totalChunksTicked.get();
        }

        public long tickCount() {
            return this.tickCount.get();
        }

        /**
         * 매 tick 끝에서 호출 — sliding window용 카운터를 0으로 되돌림.
         * 누적 통계는 보존된다.
         */
        public void resetTickWindow() {
            // 누적 통계는 건드리지 않는다. lastTick* 만 갱신.
            this.lastTickStartNanos.set(System.nanoTime());
        }
    }

    /**
     * 서버가 살아있는 동안 디버그 보고용. shutdown 후에는 빈 리스트.
     */
    public static List<String> budgetReport() {
        if (BUDGETS.isEmpty()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>(BUDGETS.size());
        for (final var entry : BUDGETS.entrySet()) {
            final ServerLevel level = entry.getKey();
            final WorldTickBudget budget = entry.getValue();
            if (level == null || budget == null) {
                continue;
            }
            out.add(worldLabel(level)
                + ": avg=" + String.format("%.3fms", budget.averageTickMillis())
                + " last=" + String.format("%.3fms", budget.lastTickMillis())
                + " ticks=" + budget.tickCount()
                + " entities=" + budget.entitiesTicked()
                + " chunks=" + budget.chunksTicked());
        }
        return out;
    }

    private static String worldLabel(final ServerLevel level) {
        if (level == null) {
            return "unknown";
        }
        try {
            // 1.21+ ServerLevel exposes getWorld().getName() via CraftBukkit.
            // Use toString fallback to stay decoupled from MC version churn.
            return level.toString();
        } catch (final Exception e) {
            return "world-" + System.identityHashCode(level);
        }
    }
}
