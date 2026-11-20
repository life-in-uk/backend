package info.lifeinuk.backend.marketindices;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Retention on its own daemon thread, every {@code retention-interval}, independent of uploads: an ingestion
 * acknowledgement never waits for it. Each run deletes in chunks of {@link #CHUNK} rows (one short autocommit
 * statement each) and stops after {@link #MAX_ROWS_PER_RUN} rows or {@link #MAX_RUN_TIME}, so a large backlog is
 * worked off over several runs without long locks. Failures are logged and retried at the next run.
 */
@Component
class MarketIndexRetention {
    private static final Logger log = LoggerFactory.getLogger(MarketIndexRetention.class);
    static final int CHUNK = 1000;
    static final int MAX_ROWS_PER_RUN = 20_000;
    static final Duration MAX_RUN_TIME = Duration.ofSeconds(60);
    static final Duration INITIAL_DELAY = Duration.ofMinutes(10);

    record Result(int observations, int batches, boolean complete) { }

    private final MarketIndexStore store;
    private final MarketIndexProperties properties;
    private final Clock clock;
    private ScheduledExecutorService executor;

    @Autowired
    MarketIndexRetention(MarketIndexStore store, MarketIndexProperties properties) { this(store, properties, Clock.systemUTC()); }

    MarketIndexRetention(MarketIndexStore store, MarketIndexProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "market-index-retention");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runSafely, INITIAL_DELAY.toSeconds(),
                properties.retentionInterval().toSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    synchronized void stop() {
        if (executor != null) executor.shutdownNow();
    }

    private void runSafely() {
        try {
            var result = run();
            if (result.observations() > 0 || result.batches() > 0) {
                log.info("Market index retention removed {} observations and {} batches older than {} (complete={})",
                        result.observations(), result.batches(), properties.retention(), result.complete());
            }
        } catch (RuntimeException failure) {
            log.warn("Market index retention failed ({}); retrying at the next run", failure.getClass().getSimpleName());
        }
    }

    /** One bounded run. {@code complete} is false when a limit stopped it with work left. */
    Result run() {
        Instant started = clock.instant();
        Instant cutoff = started.minus(properties.retention());
        int observations = 0;
        int batches = 0;
        while (observations < MAX_ROWS_PER_RUN && !timeUp(started)) {
            int deleted = store.deleteObservationsBefore(cutoff, CHUNK);
            observations += deleted;
            if (deleted < CHUNK) {
                while (batches < MAX_ROWS_PER_RUN && !timeUp(started)) {
                    int removed = store.deleteOrphanBatchesBefore(cutoff, CHUNK);
                    batches += removed;
                    if (removed < CHUNK) return new Result(observations, batches, true);
                }
                return new Result(observations, batches, false);
            }
        }
        return new Result(observations, batches, false);
    }

    private boolean timeUp(Instant started) { return Duration.between(started, clock.instant()).compareTo(MAX_RUN_TIME) >= 0; }
}
