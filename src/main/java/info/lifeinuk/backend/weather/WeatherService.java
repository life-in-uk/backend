package info.lifeinuk.backend.weather;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static info.lifeinuk.backend.weather.WeatherFailure.Reason.*;

/**
 * Current-hour weather for the fixed cities, read from the Met Office in the background and kept in memory.
 *
 * <p>Quota: Global spot data is updated hourly, so a city is re-read at most once per {@link #REFRESH}; each hourly
 * response covers 48 hours, so the step for the current hour is picked from the stored forecast at request time.
 * After a failed or rejected read the city is not retried for {@link #RETRY_AFTER_FAILURE}. Before every provider
 * call one read is taken from a {@link #DAILY_CALL_BUDGET} per UTC day kept in PostgreSQL ({@link WeatherCallBudget};
 * the free plan allows 360 and resets at 00:00 UTC), so restarts do not reset it.
 *
 * <p>Requests never wait for a refresh while a usable stored forecast exists: the refresh runs on a small background
 * pool and the stored step is answered at once. With nothing usable stored, a request waits at most
 * {@link #COLD_WAIT} for the read. Concurrent requests for a city share one read.
 *
 * <p>A new forecast replaces the stored one only if it is usable: its model run is neither in the future nor older
 * than {@link #MAX_MODEL_AGE}, not older than the stored one, and it has a step for the current hour with a temperature
 * or a nameable weather code ({@link #displayable}). A stored forecast
 * is served for up to {@link #MAX_STALE} after it was read, while the Met Office cannot be reached.
 */
@Service
public class WeatherService {
    static final Duration REFRESH = Duration.ofMinutes(60);
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);
    static final Duration MAX_STALE = Duration.ofHours(6);
    static final Duration MAX_MODEL_AGE = Duration.ofHours(12);
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    static final Duration COLD_WAIT = Duration.ofSeconds(3);
    static final int DAILY_CALL_BUDGET = 300;
    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final MetOfficeClient client;
    private final WeatherCallBudget budget;
    private final Clock clock;
    private final Executor executor;
    private final Duration coldWait;
    private final ExecutorService ownedExecutor;
    private final Map<WeatherCity, Slot> slots = new EnumMap<>(WeatherCity.class);

    private record Stored(HourlyForecast forecast, Instant fetchedAt) { }

    private static final class Slot {
        volatile Stored stored;
        // Guarded by the slot.
        Instant failedAt;
        WeatherFailure lastFailure;
        CompletableFuture<WeatherFailure> refreshing;
    }

    @Autowired
    WeatherService(MetOfficeClient client, WeatherCallBudget budget) {
        this(client, budget, Clock.systemUTC(), refreshPool(), COLD_WAIT);
    }

    WeatherService(MetOfficeClient client, WeatherCallBudget budget, Clock clock, Executor executor, Duration coldWait) {
        this.client = client;
        this.budget = budget;
        this.clock = clock;
        this.executor = executor;
        this.coldWait = coldWait;
        this.ownedExecutor = executor instanceof ExecutorService pool ? pool : null;
        for (WeatherCity city : WeatherCity.values()) { slots.put(city, new Slot()); }
    }

    /** At most one read per city is in flight, so a few daemon threads are enough. */
    private static ExecutorService refreshPool() {
        return Executors.newFixedThreadPool(4, Thread.ofPlatform().name("weather-refresh-", 0).daemon().factory());
    }

    @PreDestroy
    void close() {
        if (ownedExecutor != null) { ownedExecutor.shutdownNow(); }
    }

    public WeatherResponse current(WeatherCity city) {
        if (!client.configured()) { throw new WeatherFailure(NOT_CONFIGURED); }
        Slot slot = slots.get(city);
        CompletableFuture<WeatherFailure> refresh = refreshIfDue(city, slot, clock.instant());
        Optional<WeatherResponse> stored = answer(city, slot.stored, clock.instant());
        if (stored.isPresent()) { return stored.get(); }

        WeatherFailure failure = refresh == null ? lastFailure(slot) : awaitCold(refresh);
        if (failure != null && failure.reason() == NOT_CONFIGURED) { throw failure; }
        return answer(city, slot.stored, clock.instant())
                .orElseThrow(() -> failure != null ? failure : new WeatherFailure(UNAVAILABLE));
    }

    private WeatherFailure lastFailure(Slot slot) {
        synchronized (slot) { return slot.lastFailure; }
    }

    private WeatherFailure awaitCold(CompletableFuture<WeatherFailure> refresh) {
        try {
            return refresh.get(coldWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            return new WeatherFailure(UNAVAILABLE); // the read carries on in the background for the next request
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new WeatherFailure(UNAVAILABLE);
        } catch (ExecutionException failed) {
            return new WeatherFailure(UNAVAILABLE);
        }
    }

    /** Starts (or joins) the city's background read when one is due; null when none is due or allowed yet. */
    private CompletableFuture<WeatherFailure> refreshIfDue(WeatherCity city, Slot slot, Instant now) {
        synchronized (slot) {
            if (slot.refreshing != null) { return slot.refreshing; }
            Stored stored = slot.stored;
            boolean due = stored == null || !now.isBefore(stored.fetchedAt().plus(REFRESH));
            boolean backedOff = slot.failedAt != null && now.isBefore(slot.failedAt.plus(RETRY_AFTER_FAILURE));
            if (!due || backedOff) { return null; }
            CompletableFuture<WeatherFailure> refresh;
            try {
                refresh = CompletableFuture.supplyAsync(() -> refresh(city, slot), executor);
            } catch (RuntimeException rejected) { // pool shut down
                return null;
            }
            slot.refreshing = refresh;
            refresh.whenComplete((result, error) -> { synchronized (slot) { slot.refreshing = null; } });
            return refresh;
        }
    }

    /** One background read; returns null when the stored forecast was replaced, else the failure. */
    private WeatherFailure refresh(WeatherCity city, Slot slot) {
        WeatherFailure failure;
        try {
            if (!budget.take(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), DAILY_CALL_BUDGET)) {
                failure = new WeatherFailure(UNAVAILABLE);
                log.warn("Weather read budget of {} for today is used or unavailable; not reading {}", DAILY_CALL_BUDGET, city.id());
            } else {
                HourlyForecast forecast = HourlyForecast.parse(client.hourly(city));
                Instant now = clock.instant();
                failure = rejection(forecast, slot.stored, now);
                if (failure == null) {
                    slot.stored = new Stored(forecast, now);
                    synchronized (slot) { slot.failedAt = null; slot.lastFailure = null; }
                    return null;
                }
                log.warn("Weather forecast for {} rejected: model run {}", city.id(), forecast.modelRunAt());
            }
        } catch (WeatherFailure failed) {
            failure = failed;
            if (failed.reason() != NOT_CONFIGURED) { log.warn("Weather read failed for {}: {}", city.id(), failed.reason()); }
        } catch (RuntimeException unexpected) {
            failure = new WeatherFailure(UNAVAILABLE);
            log.warn("Weather read failed for {}: {}", city.id(), unexpected.getClass().getSimpleName());
        }
        synchronized (slot) {
            slot.failedAt = clock.instant();
            slot.lastFailure = failure;
        }
        return failure;
    }

    /** Why a newly read forecast must not replace the stored one, or null if it may. */
    private static WeatherFailure rejection(HourlyForecast forecast, Stored stored, Instant now) {
        Instant run = forecast.modelRunAt();
        if (run.isAfter(now.plus(MAX_CLOCK_SKEW))) { return new WeatherFailure(INVALID_RESPONSE); }
        if (run.isBefore(now.minus(MAX_MODEL_AGE))) { return new WeatherFailure(UNAVAILABLE); }
        if (stored != null && run.isBefore(stored.forecast().modelRunAt())) { return new WeatherFailure(UNAVAILABLE); }
        if (forecast.currentStep(now).filter(WeatherService::displayable).isEmpty()) { return new WeatherFailure(UNAVAILABLE); }
        return null;
    }

    /**
     * Whether the homepage can show anything for a step: a temperature, or a weather code it can name (every
     * published code except 4, "Not used"). A step with neither is unusable.
     */
    static boolean displayable(HourlyForecast.Step step) {
        Integer code = step.weatherCode();
        return step.temperatureC() != null || (code != null && code != 4);
    }

    private static Optional<WeatherResponse> answer(WeatherCity city, Stored stored, Instant now) {
        if (stored == null || now.isAfter(stored.fetchedAt().plus(MAX_STALE))) { return Optional.empty(); }
        return stored.forecast().currentStep(now).filter(WeatherService::displayable).map(step -> new WeatherResponse(city.id(), iso(step.time()),
                iso(stored.forecast().modelRunAt()), iso(stored.fetchedAt()), step.temperatureC(), step.feelsLikeC(),
                step.weatherCode(), step.precipitationProbability(), WeatherResponse.ATTRIBUTION, "requested"));
    }

    private static String iso(Instant instant) { return instant.truncatedTo(ChronoUnit.SECONDS).toString(); }
}
