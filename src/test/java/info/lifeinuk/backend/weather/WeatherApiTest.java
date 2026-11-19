package info.lifeinuk.backend.weather;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Public HTTP contract with the Met Office boundary mocked: no live provider access. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class WeatherApiTest {
    @LocalServerPort int port;
    @MockitoBean MetOfficeClient client;
    @Autowired MetOfficeProperties properties;
    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void reset() { org.mockito.Mockito.reset(client); }

    HttpResponse<String> get(String rawQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/weather/current" + rawQuery))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void defaultConfigurationHasNoKeyAndAnswersNotConfiguredWithoutCallingTheMetOffice() throws Exception {
        assertThat(properties.hasUsableApiKey()).isFalse();
        assertThat(properties.baseUrl()).isEqualTo(MetOfficeProperties.CANONICAL_BASE_URL);
        when(client.configured()).thenReturn(false);
        var response = get("?city=london");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).get("code").stringValue()).isEqualTo("WEATHER_NOT_CONFIGURED");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        verify(client, never()).hourly(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?city=", "?city=London", "?city=paris", "?city=london%00"})
    void unknownCitiesAreRejectedWithoutAnyRequest(String query) throws Exception {
        when(client.configured()).thenReturn(true);
        var response = get(query);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("code").stringValue()).isEqualTo("WEATHER_CITY_INVALID");
        verify(client, never()).hourly(any());
    }

    @Test
    void currentWeatherReturnsOnlyTheNormalisedContract() throws Exception {
        when(client.configured()).thenReturn(true);
        String now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString();
        when(client.hourly(WeatherCity.GLASGOW)).thenReturn(HourlyForecastTest.bytes(HourlyForecastTest.response(
                now, HourlyForecastTest.step(now, 4.6, 12))));
        var response = get("?city=glasgow");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("city", "forecastTime", "modelRunAt", "fetchedAt",
                "temperatureC", "feelsLikeC", "weatherCode", "precipitationProbability", "attribution");
        assertThat(body.get("city").stringValue()).isEqualTo("glasgow");
        assertThat(body.get("forecastTime").stringValue()).isEqualTo(now);
        assertThat(body.get("temperatureC").asDouble()).isEqualTo(4.6);
        assertThat(body.get("weatherCode").asInt()).isEqualTo(12);
        assertThat(body.get("attribution").stringValue()).isEqualTo("Data supplied by the Met Office");
    }

    @Test
    void upstreamFailuresMapToBoundedJsonErrors() throws Exception {
        when(client.configured()).thenReturn(true);
        when(client.hourly(WeatherCity.BELFAST)).thenThrow(new WeatherFailure(WeatherFailure.Reason.UNAVAILABLE));
        var unavailable = get("?city=belfast");
        assertThat(unavailable.statusCode()).isEqualTo(502);
        assertThat(json.readTree(unavailable.body()).get("code").stringValue()).isEqualTo("WEATHER_UNAVAILABLE");
        when(client.hourly(WeatherCity.EDINBURGH)).thenReturn("not json".getBytes(StandardCharsets.UTF_8));
        var invalid = get("?city=edinburgh");
        assertThat(invalid.statusCode()).isEqualTo(502);
        assertThat(json.readTree(invalid.body()).get("code").stringValue()).isEqualTo("WEATHER_UPSTREAM_INVALID");
    }

    // --- The daily read budget in PostgreSQL (V16 weather_call_budget) ---

    @Test
    void theBudgetIsAtomicUnderConcurrency() throws Exception {
        LocalDate day = LocalDate.parse("2101-01-01");
        var budget = new WeatherCallBudget(jdbc);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(16);
        try {
            var taken = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 40; i++) {
                Callable<Boolean> take = () -> { start.await(); return budget.take(day, 7); };
                taken.add(pool.submit(take));
            }
            start.countDown();
            int granted = 0;
            for (var result : taken) { if (result.get()) { granted++; } }
            assertThat(granted).isEqualTo(7);
            assertThat(budget.used(day)).isEqualTo(7);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theBudgetSurvivesARestartAndRefusesBeforeAnyProviderCall() {
        LocalDate day = LocalDate.parse("2102-02-02");
        var beforeRestart = new WeatherCallBudget(jdbc);
        for (int i = 0; i < WeatherService.DAILY_CALL_BUDGET; i++) { assertThat(beforeRestart.take(day, WeatherService.DAILY_CALL_BUDGET)).isTrue(); }
        // A new process: new service and budget objects over the same database.
        var afterRestart = new WeatherCallBudget(jdbc);
        assertThat(afterRestart.used(day)).isEqualTo(WeatherService.DAILY_CALL_BUDGET);
        var provider = mock(MetOfficeClient.class);
        when(provider.configured()).thenReturn(true);
        var clock = Clock.fixed(Instant.parse("2102-02-02T12:00:00Z"), ZoneOffset.UTC);
        var service = new WeatherService(provider, afterRestart, clock, Runnable::run, Duration.ofMillis(100));
        var thrown = catchThrowableOfType(WeatherFailure.class, () -> service.current(WeatherCity.LONDON));
        assertThat(thrown.reason()).isEqualTo(WeatherFailure.Reason.UNAVAILABLE);
        verify(provider, never()).hourly(any());
        assertThat(afterRestart.used(day)).isEqualTo(WeatherService.DAILY_CALL_BUDGET);
        // The next UTC day starts again.
        assertThat(afterRestart.take(day.plusDays(1), WeatherService.DAILY_CALL_BUDGET)).isTrue();
    }

    @Test
    void theApiCountsEachProviderReadInTheDatabase() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int before = new WeatherCallBudget(jdbc).used(today);
        when(client.configured()).thenReturn(true);
        String now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString();
        when(client.hourly(WeatherCity.CARDIFF)).thenReturn(HourlyForecastTest.bytes(HourlyForecastTest.response(
                now, HourlyForecastTest.step(now, 9.1, 3))));
        assertThat(get("?city=cardiff").statusCode()).isEqualTo(200);
        assertThat(get("?city=cardiff").statusCode()).isEqualTo(200);
        verify(client, times(1)).hourly(WeatherCity.CARDIFF);
        assertThat(new WeatherCallBudget(jdbc).used(today)).isEqualTo(before + 1);
    }
}
