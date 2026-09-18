package agc.bench;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Orchestrates a pool of bots: staggered joins, 20 Hz scenario dispatch, and churn handling
 * for login-storm scenarios.
 */
public final class BotFarm implements AutoCloseable {

    private final Supplier<BotHandle> botFactory;
    private final List<BotHandle> bots = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(4, r -> {
            final Thread t = new Thread(r, "agc-bench-farm");
            t.setDaemon(true);
            return t;
        });

    private final AtomicInteger joined = new AtomicInteger();
    private volatile double churnPerSecond = 0.0;
    private volatile boolean running = true;

    public BotFarm(final Supplier<BotHandle> botFactory) {
        this.botFactory = botFactory;
    }

    /** Spawns {@code count} bots, joining at {@code joinRatePerSecond}. */
    public void spawnStaged(final int count, final double joinRatePerSecond) throws InterruptedException {
        final long intervalMillis = Math.max(1L, (long) (1000.0 / Math.max(0.1, joinRatePerSecond)));
        for (int i = 0; i < count; i++) {
            final BotHandle bot = botFactory.get();
            bot.connect();
            this.bots.add(bot);
            this.joined.incrementAndGet();
            Thread.sleep(intervalMillis);
        }
    }

    /** Staggers bot ages uniformly over [0, lifetimeTicks) so sessions do not expire in lockstep bursts. */
    public void staggerBotAges(final int lifetimeTicks) {
        if (lifetimeTicks <= 0 || this.bots.isEmpty()) return;
        final int size = this.bots.size();
        int i = 0;
        for (final BotHandle bot : this.bots) {
            final int age = (int) ((long) i * lifetimeTicks / size);
            bot.setAgeTicks(age);
            i++;
        }
    }

    /** Starts the 20 Hz scenario tick loop. */
    public void startScenarioLoop(final Scenario scenario) {
        this.churnPerSecond = scenario.churnPerSecond();
        this.scheduler.scheduleAtFixedRate(() -> {
            if (!this.running) {
                return;
            }
            this.tickIndex.incrementAndGet();
            for (final BotHandle bot : this.bots) {
                try {
                    if (bot.isConnected()) {
                        bot.incrementAge();
                        scenario.onBotTick(bot, bot.getAgeTicks());
                    }
                } catch (final Throwable t) {
                    // Never let one bad bot kill the farm loop.
                    System.err.println("[bench] bot tick error: " + t);
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    private final AtomicInteger tickIndex = new AtomicInteger();

    /** Login-churn driver: connects + disconnects sessions at the scenario rate. */
    public void startChurnLoop(final double perSecond) {
        final long intervalMillis = Math.max(1L, (long) (1000.0 / Math.max(0.1, perSecond)));
        this.scheduler.scheduleAtFixedRate(() -> {
            if (!this.running) {
                return;
            }
            try {
                this.bots.removeIf(b -> !b.isConnected());
                final BotHandle fresh = this.botFactory.get();
                this.scheduler.execute(() -> {
                    try {
                        fresh.connect();
                        this.bots.add(fresh);
                    } catch (final Throwable ignored) {}
                });
            } catch (final Throwable t) {
                System.err.println("[bench] churn error: " + t);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public int connectedCount() {
        return (int) this.bots.stream().filter(BotHandle::isConnected).count();
    }

    public int totalJoined() {
        return this.joined.get();
    }

    @Override
    public void close() {
        this.running = false;
        this.scheduler.shutdownNow();
        for (final BotHandle bot : this.bots) {
            try {
                bot.disconnect();
            } catch (final Throwable ignored) {}
        }
        this.bots.clear();
    }
}
