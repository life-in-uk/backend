package info.lifeinuk.backend.refresh;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One source's schedule on its own single daemon thread: run, then schedule the next run after the
 * policy's delay (fixed delay, so runs never overlap). Stops after {@code maxRuns} provider runs when
 * bounded; SKIPPED ticks do not contact the provider and are not counted.
 */
final class RefreshLoop {
    private static final Logger log = LoggerFactory.getLogger(RefreshLoop.class);
    private final String name;
    private final RefreshPolicy policy;
    private final int maxRuns;
    private final Supplier<RefreshPolicy.Outcome> task;
    private final ScheduledExecutorService executor;
    private int runs;

    RefreshLoop(String name, RefreshPolicy policy, int maxRuns, Supplier<RefreshPolicy.Outcome> task) {
        this.name = name;
        this.policy = policy;
        this.maxRuns = maxRuns;
        this.task = task;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "travel-refresh-" + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    void start(Duration initialDelay) {
        log.info("Travel refresh {} enabled; first run in {}{}", name, initialDelay,
                maxRuns > 0 ? ", bounded to " + maxRuns + " runs" : "");
        schedule(initialDelay);
    }

    boolean isRunning() { return !executor.isShutdown(); }

    synchronized int runs() { return runs; }

    void stop() {
        executor.shutdownNow();
    }

    boolean awaitStopped(Duration timeout) throws InterruptedException {
        return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void schedule(Duration delay) {
        if (!executor.isShutdown()) {
            executor.schedule(this::tick, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void tick() {
        long started = System.nanoTime();
        RefreshPolicy.Outcome outcome;
        try {
            outcome = task.get();
        } catch (RuntimeException unexpected) {
            log.warn("Travel refresh {} failed unexpectedly: {}", name, unexpected.getClass().getSimpleName());
            outcome = RefreshPolicy.Outcome.FAILED;
        }
        Duration next = policy.next(outcome);
        int completed;
        synchronized (this) {
            if (outcome != RefreshPolicy.Outcome.SKIPPED) {
                runs++;
            }
            completed = runs;
        }
        log.info("Travel refresh {} outcome={} durationMs={} consecutiveFailures={} next={}", name, outcome,
                Duration.ofNanos(System.nanoTime() - started).toMillis(), policy.consecutiveFailures(), next);
        if (maxRuns > 0 && completed >= maxRuns) {
            log.info("Travel refresh {} reached max-runs {}; schedule stopped", name, maxRuns);
            executor.shutdown();
            return;
        }
        schedule(next);
    }
}
