package info.lifeinuk.backend.weather;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static info.lifeinuk.backend.weather.WeatherFailure.Reason.*;

/**
 * Current-hour weather for the fixed cities, read from the Met Office on demand and kept in memory.
 *
 * <p>Quota: Global spot data is updated hourly, so a city is re-read at most once per {@link #REFRESH}; each hourly
 * response covers 48 hours, so the step for the current hour is picked from the stored forecast at request time.
 * After a failed read the city is not retried for {@link #RETRY_AFTER_FAILURE}, and no more than
 * {@link #DAILY_CALL_BUDGET} reads are made per UTC day (the Met Office free plan allows 360 and resets at 00:00 UTC).
 * Visitors never cause more than that: concurrent requests for a city wait for the single read in progress.
 * A stored forecast is served for up to {@link #MAX_STALE} while the Met Office cannot be reached.
 */
@Service
public class WeatherService {
    static final Duration REFRESH = Duration.ofMinutes(60);
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);
    static final Duration MAX_STALE = Duration.ofHours(6);
    static final Duration STEP_TOLERANCE = Duration.ofMinutes(90);
    static final int DAILY_CALL_BUDGET = 300;
    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final MetOfficeClient client;
    private final Clock clock;
    private final Map<WeatherCity, Slot> slots = new EnumMap<>(WeatherCity.class);
    private LocalDate budgetDay;
    private int callsToday;

    private static final class Slot {
        HourlyForecast forecast;
        Instant fetchedAt;
        Instant failedAt;
    }

    @Autowired
    WeatherService(MetOfficeClient client) { this(client, Clock.systemUTC()); }

    WeatherService(MetOfficeClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
        for (WeatherCity city : WeatherCity.values()) { slots.put(city, new Slot()); }
    }

    public WeatherResponse current(WeatherCity city) {
        if (!client.configured()) { throw new WeatherFailure(NOT_CONFIGURED); }
        Slot slot = slots.get(city);
        synchronized (slot) {
            Instant now = clock.instant();
            WeatherFailure failure = null;
            boolean due = slot.fetchedAt == null || !now.isBefore(slot.fetchedAt.plus(REFRESH));
            boolean backedOff = slot.failedAt != null && now.isBefore(slot.failedAt.plus(RETRY_AFTER_FAILURE));
            if (due && !backedOff) {
                if (takeBudget(now)) {
                    try {
                        slot.forecast = HourlyForecast.parse(client.hourly(city));
                        slot.fetchedAt = now;
                        slot.failedAt = null;
                    } catch (WeatherFailure failed) {
                        if (failed.reason() == NOT_CONFIGURED) { throw failed; }
                        slot.failedAt = now;
                        failure = failed;
                        log.warn("Weather read failed for {}: {}", city.id(), failed.reason());
                    }
                } else {
                    failure = new WeatherFailure(UNAVAILABLE);
                }
            }
            if (slot.forecast == null || now.isAfter(slot.fetchedAt.plus(MAX_STALE))) {
                throw failure != null ? failure : new WeatherFailure(UNAVAILABLE);
            }
            HourlyForecast.Step step = slot.forecast.stepAt(now, STEP_TOLERANCE)
                    .orElseThrow(() -> new WeatherFailure(UNAVAILABLE));
            return new WeatherResponse(city.id(), iso(step.time()), iso(slot.forecast.modelRunAt()), iso(slot.fetchedAt),
                    step.temperatureC(), step.feelsLikeC(), step.weatherCode(), step.precipitationProbability(),
                    WeatherResponse.ATTRIBUTION);
        }
    }

    /** One read from the daily budget, or false when it is used up (counted per UTC day). */
    private synchronized boolean takeBudget(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!today.equals(budgetDay)) {
            budgetDay = today;
            callsToday = 0;
        }
        if (callsToday >= DAILY_CALL_BUDGET) {
            if (callsToday == DAILY_CALL_BUDGET) {
                log.warn("Weather daily read budget of {} reached; serving stored forecasts until 00:00 UTC", DAILY_CALL_BUDGET);
                callsToday++;
            }
            return false;
        }
        callsToday++;
        return true;
    }

    private static String iso(Instant instant) { return instant.truncatedTo(ChronoUnit.SECONDS).toString(); }
}
