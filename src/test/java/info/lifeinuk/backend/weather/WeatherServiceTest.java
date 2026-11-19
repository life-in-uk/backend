package info.lifeinuk.backend.weather;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static info.lifeinuk.backend.weather.HourlyForecastTest.*;
import static info.lifeinuk.backend.weather.WeatherFailure.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Cache, refresh, freshness, quota and staleness rules with a scripted Met Office boundary: offline. */
class WeatherServiceTest {
    private static final Duration COLD_WAIT = Duration.ofMillis(100);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T08:20:00Z"));
    private final MemoryBudget budget = new MemoryBudget();
    private MetOfficeClient client;
    private WeatherService service;

    /** 48 hourly steps from 06:00 on the day, run at 05:00, temperature = hour of day. */
    static byte[] forecastFrom(String day) { return forecast(day + "T05:00Z", Instant.parse(day + "T06:00:00Z"), 48); }

    static byte[] forecast(String modelRun, Instant first, int hours) {
        var steps = new ArrayList<String>();
        for (int hour = 0; hour < hours; hour++) {
            Instant time = first.plus(Duration.ofHours(hour));
            steps.add(step(time.toString(), time.atZone(ZoneOffset.UTC).getHour(), 7));
        }
        return bytes(response(modelRun, steps.toArray(String[]::new)));
    }

    /** Runs background reads inline: the service then behaves synchronously. */
    private WeatherService inline() { return new WeatherService(client, budget, clock, Runnable::run, COLD_WAIT); }

    @BeforeEach
    void setUp() {
        client = mock(MetOfficeClient.class);
        when(client.configured()).thenReturn(true);
        when(client.hourly(any())).thenReturn(forecastFrom("2026-11-19"));
        service = inline();
    }

    private static WeatherFailure.Reason failure(Runnable call) {
        var thrown = catchThrowableOfType(WeatherFailure.class, call::run);
        assertThat(thrown).isNotNull();
        return thrown.reason();
    }

    @Test
    void returnsTheCurrentHourStepWithAttributionAndTimes() {
        var response = service.current(WeatherCity.LONDON);
        assertThat(response.city()).isEqualTo("london");
        assertThat(response.forecastTime()).isEqualTo("2026-11-19T08:00:00Z");
        assertThat(response.modelRunAt()).isEqualTo("2026-11-19T05:00:00Z");
        assertThat(response.fetchedAt()).isEqualTo("2026-11-19T08:20:00Z");
        assertThat(response.temperatureC()).isEqualTo(8.0);
        assertThat(response.feelsLikeC()).isEqualTo(5.5);
        assertThat(response.weatherCode()).isEqualTo(7);
        assertThat(response.precipitationProbability()).isEqualTo(12);
        assertThat(response.attribution()).isEqualTo("Data supplied by the Met Office");
    }

    @Test
    void notConfiguredMakesNoRequestAndTakesNoBudget() {
        when(client.configured()).thenReturn(false);
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(NOT_CONFIGURED);
        verify(client, never()).hourly(any());
        assertThat(budget.used(LocalDate.parse("2026-11-19"))).isZero();
    }

    @Test
    void aCityIsReadAtMostOncePerHourAndTheStepFollowsTheClock() {
        service.current(WeatherCity.LONDON);
        clock.advance(Duration.ofMinutes(45));
        var later = service.current(WeatherCity.LONDON);
        assertThat(later.forecastTime()).isEqualTo("2026-11-19T09:00:00Z");
        assertThat(later.fetchedAt()).isEqualTo("2026-11-19T08:20:00Z");
        verify(client, times(1)).hourly(WeatherCity.LONDON);
        clock.advance(Duration.ofMinutes(15));
        assertThat(service.current(WeatherCity.LONDON).fetchedAt()).isEqualTo("2026-11-19T09:20:00Z");
        verify(client, times(2)).hourly(WeatherCity.LONDON);
        // Cities are cached separately.
        service.current(WeatherCity.CARDIFF);
        verify(client, times(1)).hourly(WeatherCity.CARDIFF);
    }

    @Test
    void aFailedReadServesTheStoredForecastBacksOffAndExpiresAfterSixHours() {
        service.current(WeatherCity.LONDON);
        when(client.hourly(any())).thenThrow(new WeatherFailure(UNAVAILABLE));
        clock.advance(Duration.ofMinutes(61));
        assertThat(service.current(WeatherCity.LONDON).fetchedAt()).isEqualTo("2026-11-19T08:20:00Z");
        verify(client, times(2)).hourly(WeatherCity.LONDON);
        // Within the back-off: no new request.
        clock.advance(Duration.ofMinutes(9));
        service.current(WeatherCity.LONDON);
        verify(client, times(2)).hourly(WeatherCity.LONDON);
        clock.advance(Duration.ofMinutes(1));
        service.current(WeatherCity.LONDON);
        verify(client, times(3)).hourly(WeatherCity.LONDON);
        // Six hours after the last good read nothing is served.
        clock.set(Instant.parse("2026-11-19T14:20:01Z"));
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(UNAVAILABLE);
    }

    @Test
    void aFirstReadFailureReportsItsReasonAndBacksOff() {
        when(client.hourly(any())).thenThrow(new WeatherFailure(INVALID_RESPONSE));
        assertThat(failure(() -> service.current(WeatherCity.LEEDS))).isEqualTo(INVALID_RESPONSE);
        // Backing off: the next visitor gets the same answer without another request.
        assertThat(failure(() -> service.current(WeatherCity.LEEDS))).isEqualTo(INVALID_RESPONSE);
        verify(client, times(1)).hourly(WeatherCity.LEEDS);
    }

    @Test
    void anUnexpectedErrorInARefreshIsUnavailableNotA500() {
        when(client.hourly(any())).thenThrow(new IllegalStateException("boom"));
        assertThat(failure(() -> service.current(WeatherCity.LEEDS))).isEqualTo(UNAVAILABLE);
    }

    // --- Freshness: a new forecast replaces the stored one only if it is usable ---

    @Test
    void aForecastFromAnOldModelRunIsRejected() {
        when(client.hourly(any())).thenReturn(forecastFrom("2026-11-17"));
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(UNAVAILABLE);
        // Covers now, but its model run is more than 12 hours old.
        when(client.hourly(any())).thenReturn(forecast("2026-11-18T20:00Z", Instant.parse("2026-11-19T06:00:00Z"), 48));
        clock.advance(Duration.ofMinutes(10));
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(UNAVAILABLE);
    }

    @Test
    void aForecastFromTheFutureIsInvalid() {
        when(client.hourly(any())).thenReturn(forecast("2026-11-19T10:00Z", Instant.parse("2026-11-19T06:00:00Z"), 48));
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(INVALID_RESPONSE);
    }

    @Test
    void aForecastWithoutAStepForTheCurrentHourIsNotShownAndNeverReplacesAUsableOne() {
        // Starts at 10:00: no step for 08:20, and the 10:00 step is not shown in its place.
        when(client.hourly(any())).thenReturn(forecast("2026-11-19T07:00Z", Instant.parse("2026-11-19T10:00:00Z"), 48));
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(UNAVAILABLE);
        // A usable forecast is stored; at the next refresh the provider returns one with a gap at the current hour.
        clock.advance(Duration.ofMinutes(10));
        when(client.hourly(any())).thenReturn(forecastFrom("2026-11-19"));
        assertThat(service.current(WeatherCity.LONDON).forecastTime()).isEqualTo("2026-11-19T08:00:00Z");
        clock.advance(Duration.ofMinutes(60)); // 09:30
        when(client.hourly(any())).thenReturn(forecast("2026-11-19T08:00Z", Instant.parse("2026-11-19T10:00:00Z"), 48));
        var kept = service.current(WeatherCity.LONDON);
        assertThat(kept.forecastTime()).isEqualTo("2026-11-19T09:00:00Z");
        assertThat(kept.modelRunAt()).isEqualTo("2026-11-19T05:00:00Z");
        assertThat(kept.fetchedAt()).isEqualTo("2026-11-19T08:30:00Z");
    }

    @Test
    void anOlderModelRunNeverReplacesANewerOne() {
        when(client.hourly(any())).thenReturn(forecast("2026-11-19T07:00Z", Instant.parse("2026-11-19T07:00:00Z"), 48));
        assertThat(service.current(WeatherCity.LONDON).modelRunAt()).isEqualTo("2026-11-19T07:00:00Z");
        clock.advance(Duration.ofMinutes(61));
        when(client.hourly(any())).thenReturn(forecastFrom("2026-11-19")); // run 05:00
        var kept = service.current(WeatherCity.LONDON);
        assertThat(kept.modelRunAt()).isEqualTo("2026-11-19T07:00:00Z");
        assertThat(kept.fetchedAt()).isEqualTo("2026-11-19T08:20:00Z");
        // The same run again is fine: it refreshes the read time.
        clock.advance(Duration.ofMinutes(10));
        when(client.hourly(any())).thenReturn(forecast("2026-11-19T07:00Z", Instant.parse("2026-11-19T07:00:00Z"), 48));
        assertThat(service.current(WeatherCity.LONDON).fetchedAt()).isEqualTo("2026-11-19T09:31:00Z");
    }

    // --- A step the homepage cannot show is unusable ---

    /** One step with optional temperature and weather code (absent when null). */
    static String rawStep(String time, Double temperature, Integer code) {
        return "{\"time\":\"" + time + "\"" + (temperature == null ? "" : ",\"screenTemperature\":" + temperature)
                + (code == null ? "" : ",\"significantWeatherCode\":" + code) + "}";
    }

    static byte[] singleHour(Double temperature, Integer code) {
        return bytes(response("2026-11-19T07:00Z", rawStep("2026-11-19T08:00Z", temperature, code),
                rawStep("2026-11-19T09:00Z", temperature, code)));
    }

    @Test
    void aCurrentStepWithNeitherTemperatureNorANameableConditionIsRejected() {
        when(client.hourly(any())).thenReturn(singleHour(null, null));
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(UNAVAILABLE);
        clock.advance(Duration.ofMinutes(10));
        when(client.hourly(any())).thenReturn(singleHour(null, 4)); // 4 = "Not used": nothing to name
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(UNAVAILABLE);
        verify(client, times(2)).hourly(WeatherCity.LONDON);
    }

    @Test
    void aTemperatureAloneOrAConditionAloneIsEnough() {
        when(client.hourly(WeatherCity.LONDON)).thenReturn(singleHour(6.5, 4));
        var temperatureOnly = service.current(WeatherCity.LONDON);
        assertThat(temperatureOnly.temperatureC()).isEqualTo(6.5);
        assertThat(temperatureOnly.weatherCode()).isEqualTo(4);
        when(client.hourly(WeatherCity.LEEDS)).thenReturn(singleHour(null, 12));
        var conditionOnly = service.current(WeatherCity.LEEDS);
        assertThat(conditionOnly.temperatureC()).isNull();
        assertThat(conditionOnly.weatherCode()).isEqualTo(12);
    }

    @Test
    void anUnusableResponseNeverReplacesUsableStoredWeather() {
        assertThat(service.current(WeatherCity.LONDON).temperatureC()).isEqualTo(8.0);
        clock.advance(Duration.ofMinutes(61)); // 09:21: refresh due; the newer run's 09:00 step is empty
        when(client.hourly(any())).thenReturn(singleHour(null, null));
        var kept = service.current(WeatherCity.LONDON);
        assertThat(kept.forecastTime()).isEqualTo("2026-11-19T09:00:00Z");
        assertThat(kept.temperatureC()).isEqualTo(9.0);
        assertThat(kept.modelRunAt()).isEqualTo("2026-11-19T05:00:00Z");
        assertThat(kept.fetchedAt()).isEqualTo("2026-11-19T08:20:00Z");
        verify(client, times(2)).hourly(WeatherCity.LONDON);
    }

    // --- Requests do not wait for refreshes ---

    @Test
    void aDueRefreshRunsInTheBackgroundWhileTheStoredForecastIsAnsweredAtOnce() {
        var queue = new QueueExecutor();
        service = new WeatherService(client, budget, clock, queue, COLD_WAIT);
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(UNAVAILABLE); // cold: bounded wait
        queue.runAll();
        assertThat(service.current(WeatherCity.LONDON).fetchedAt()).isEqualTo("2026-11-19T08:20:00Z");

        clock.advance(Duration.ofMinutes(61)); // 09:21: a refresh is due
        long started = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            var answer = service.current(WeatherCity.LONDON);
            assertThat(answer.forecastTime()).isEqualTo("2026-11-19T09:00:00Z");
            assertThat(answer.fetchedAt()).isEqualTo("2026-11-19T08:20:00Z");
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(COLD_WAIT);
        assertThat(queue.size()).isEqualTo(1); // one refresh for all five requests
        verify(client, times(1)).hourly(WeatherCity.LONDON);
        queue.runAll();
        verify(client, times(2)).hourly(WeatherCity.LONDON);
        assertThat(service.current(WeatherCity.LONDON).fetchedAt()).isEqualTo("2026-11-19T09:21:00Z");
    }

    @Test
    void aColdCacheWaitsABoundedTimeAndConcurrentRequestsShareOneRead() throws Exception {
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(client.hourly(any())).thenAnswer(call -> {
            calls.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
            return forecastFrom("2026-11-19");
        });
        ExecutorService refreshPool = Executors.newFixedThreadPool(2);
        ExecutorService visitors = Executors.newFixedThreadPool(8);
        try {
            service = new WeatherService(client, budget, clock, refreshPool, COLD_WAIT);
            var answers = new ArrayList<Future<WeatherFailure.Reason>>();
            long started = System.nanoTime();
            for (int i = 0; i < 8; i++) {
                answers.add(visitors.submit(() -> failure(() -> service.current(WeatherCity.GLASGOW))));
            }
            for (var answer : answers) { assertThat(answer.get(5, TimeUnit.SECONDS)).isEqualTo(UNAVAILABLE); }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            release.countDown();
            // The read finishes in the background; the next request gets it without another read.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            WeatherResponse answer = null;
            while (answer == null && System.nanoTime() < deadline) {
                try { answer = service.current(WeatherCity.GLASGOW); } catch (WeatherFailure stillLoading) { Thread.sleep(20); }
            }
            assertThat(answer).isNotNull();
            assertThat(calls.get()).isEqualTo(1);
            assertThat(budget.used(LocalDate.parse("2026-11-19"))).isEqualTo(1);
        } finally {
            release.countDown();
            visitors.shutdownNow();
            refreshPool.shutdownNow();
        }
    }

    // --- Daily budget ---

    @Test
    void readsStopAtTheDailyBudgetAndResumeAfterMidnightUtc() {
        when(client.hourly(any())).thenThrow(new WeatherFailure(UNAVAILABLE));
        List<WeatherCity> cities = List.of(WeatherCity.values());
        int attempts = 0;
        // Failing reads retried every 10 minutes for every city would exceed the budget within the day.
        clock.set(Instant.parse("2026-11-19T00:00:00Z"));
        while (clock.instant().isBefore(Instant.parse("2026-11-20T00:00:00Z"))) {
            for (WeatherCity city : cities) {
                try { service.current(city); } catch (WeatherFailure expected) { attempts++; }
            }
            clock.advance(Duration.ofMinutes(10));
        }
        assertThat(attempts).isEqualTo(cities.size() * 144);
        verify(client, times(WeatherService.DAILY_CALL_BUDGET)).hourly(any());
        assertThat(budget.used(LocalDate.parse("2026-11-19"))).isEqualTo(WeatherService.DAILY_CALL_BUDGET);
        // A new UTC day has a new budget.
        reset(client);
        when(client.configured()).thenReturn(true);
        when(client.hourly(any())).thenReturn(forecastFrom("2026-11-20"));
        clock.set(Instant.parse("2026-11-20T08:00:00Z"));
        assertThat(service.current(WeatherCity.LONDON).temperatureC()).isEqualTo(8.0);
    }

    @Test
    void aRestartKeepsTheDaysBudget() {
        budget.usedUp(LocalDate.parse("2026-11-19"));
        WeatherService restarted = inline();
        assertThat(failure(() -> restarted.current(WeatherCity.LONDON))).isEqualTo(UNAVAILABLE);
        verify(client, never()).hourly(any());
    }

    @Test
    void anUnavailableBudgetMeansNoProviderCall() {
        budget.broken = true;
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(UNAVAILABLE);
        verify(client, never()).hourly(any());
    }

    @Test
    void normalUseStaysWellUnderTheFreePlan() {
        clock.set(Instant.parse("2026-11-19T00:00:00Z"));
        when(client.hourly(any())).thenAnswer(call -> forecast(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .minus(Duration.ofHours(2)).toString(), clock.instant().minus(Duration.ofHours(1)), 48));
        // Every city requested every minute for a whole day.
        while (clock.instant().isBefore(Instant.parse("2026-11-20T00:00:00Z"))) {
            for (WeatherCity city : WeatherCity.values()) {
                try { service.current(city); } catch (WeatherFailure ignored) { }
            }
            clock.advance(Duration.ofMinutes(1));
        }
        verify(client, atMost(WeatherCity.values().length * 24)).hourly(any());
        assertThat(WeatherCity.values().length * 24).isLessThan(360);
    }

    /** {@link WeatherCallBudget} semantics in memory (the PostgreSQL statement is tested in WeatherApiTest). */
    static final class MemoryBudget extends WeatherCallBudget {
        private final Map<LocalDate, Integer> days = new HashMap<>();
        volatile boolean broken;
        MemoryBudget() { super(null); }
        @Override synchronized boolean take(LocalDate day, int limit) {
            if (broken || days.getOrDefault(day, 0) >= limit) { return false; }
            days.merge(day, 1, Integer::sum);
            return true;
        }
        @Override synchronized int used(LocalDate day) { return days.getOrDefault(day, 0); }
        synchronized void usedUp(LocalDate day) { days.put(day, WeatherService.DAILY_CALL_BUDGET); }
    }

    /** Holds background reads until the test runs them. */
    static final class QueueExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable task) { tasks.add(task); }
        synchronized int size() { return tasks.size(); }
        void runAll() {
            Runnable task;
            while ((task = poll()) != null) { task.run(); }
        }
        private synchronized Runnable poll() { return tasks.poll(); }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        void set(Instant instant) { now = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
