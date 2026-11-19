package info.lifeinuk.backend.weather;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static info.lifeinuk.backend.weather.HourlyForecastTest.*;
import static info.lifeinuk.backend.weather.WeatherFailure.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Cache, quota and staleness rules with a scripted Met Office boundary: offline. */
class WeatherServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T08:20:00Z"));
    private MetOfficeClient client;
    private WeatherService service;

    /** 48 hourly steps from 06:00 on the clock's day, temperature = hour of day. */
    static byte[] forecastFrom(String day) {
        var steps = new ArrayList<String>();
        for (int hour = 0; hour < 48; hour++) {
            Instant time = Instant.parse(day + "T06:00:00Z").plus(Duration.ofHours(hour));
            steps.add(step(time.toString(), (6 + hour) % 24, 7));
        }
        return bytes(response(day + "T05:00Z", steps.toArray(String[]::new)));
    }

    @BeforeEach
    void setUp() {
        client = mock(MetOfficeClient.class);
        when(client.configured()).thenReturn(true);
        when(client.hourly(any())).thenReturn(forecastFrom("2026-11-19"));
        service = new WeatherService(client, clock);
    }

    private WeatherFailure.Reason failure(Runnable call) {
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
    void notConfiguredMakesNoRequest() {
        when(client.configured()).thenReturn(false);
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(NOT_CONFIGURED);
        verify(client, never()).hourly(any());
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
    void aFirstReadFailureReportsItsReason() {
        when(client.hourly(any())).thenThrow(new WeatherFailure(INVALID_RESPONSE));
        assertThat(failure(() -> service.current(WeatherCity.LEEDS))).isEqualTo(INVALID_RESPONSE);
        // Backing off: the next visitor gets "unavailable" without another request.
        assertThat(failure(() -> service.current(WeatherCity.LEEDS))).isEqualTo(UNAVAILABLE);
        verify(client, times(1)).hourly(WeatherCity.LEEDS);
    }

    @Test
    void aForecastThatDoesNotCoverNowIsNotShown() {
        when(client.hourly(any())).thenReturn(forecastFrom("2026-11-17"));
        assertThat(failure(() -> service.current(WeatherCity.LONDON))).isEqualTo(UNAVAILABLE);
    }

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
        // A new UTC day has a new budget.
        reset(client);
        when(client.configured()).thenReturn(true);
        when(client.hourly(any())).thenReturn(forecastFrom("2026-11-20"));
        clock.set(Instant.parse("2026-11-20T08:00:00Z"));
        assertThat(service.current(WeatherCity.LONDON).temperatureC()).isEqualTo(8.0);
    }

    @Test
    void normalUseStaysWellUnderTheFreePlan() {
        clock.set(Instant.parse("2026-11-19T00:00:00Z"));
        when(client.hourly(any())).thenAnswer(call -> forecastFrom("2026-11-18"));
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

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        void set(Instant instant) { now = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
