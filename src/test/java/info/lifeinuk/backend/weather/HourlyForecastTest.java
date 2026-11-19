package info.lifeinuk.backend.weather;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static info.lifeinuk.backend.weather.WeatherFailure.Reason.INVALID_RESPONSE;
import static org.assertj.core.api.Assertions.*;

class HourlyForecastTest {
    /** Shape of a Global spot hourly response (flat parameters, as returned in practice). */
    static String response(String modelRun, String... steps) {
        return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\","
                + "\"coordinates\":[-0.1278,51.5074,11]},\"properties\":{\"requestPointDistance\":120.5,\"modelRunDate\":\""
                + modelRun + "\",\"timeSeries\":[" + String.join(",", steps) + "]}}]}";
    }

    static String step(String time, double temperature, int code) {
        return "{\"time\":\"" + time + "\",\"screenTemperature\":" + temperature + ",\"feelsLikeTemperature\":"
                + (temperature - 2.5) + ",\"significantWeatherCode\":" + code + ",\"probOfPrecipitation\":12,"
                + "\"windSpeed10m\":4.1,\"uvIndex\":1}";
    }

    static byte[] bytes(String json) { return json.getBytes(StandardCharsets.UTF_8); }

    @Test
    void parsesTheFieldsTheHomepageUsesAndIgnoresTheRest() {
        var forecast = HourlyForecast.parse(bytes(response("2026-11-19T06:00Z",
                step("2026-11-19T07:00Z", 6.4, 7), step("2026-11-19T08:00Z", 7.2, 12))));
        assertThat(forecast.modelRunAt()).isEqualTo(Instant.parse("2026-11-19T06:00:00Z"));
        assertThat(forecast.steps()).hasSize(2);
        var first = forecast.steps().getFirst();
        assertThat(first.time()).isEqualTo(Instant.parse("2026-11-19T07:00:00Z"));
        assertThat(first.temperatureC()).isEqualTo(6.4);
        assertThat(first.feelsLikeC()).isCloseTo(3.9, within(1e-9));
        assertThat(first.weatherCode()).isEqualTo(7);
        assertThat(first.precipitationProbability()).isEqualTo(12);
    }

    @Test
    void acceptsTheNestedDataShapeOfThePublishedSchemaAndMissingOptionalValues() {
        var forecast = HourlyForecast.parse(bytes(response("2026-11-19T06:00:00Z",
                "{\"time\":\"2026-11-19T07:00Z\",\"data\":{\"screenTemperature\":-1.5,\"significantWeatherCode\":-1}}")));
        var step = forecast.steps().getFirst();
        assertThat(step.temperatureC()).isEqualTo(-1.5);
        assertThat(step.weatherCode()).isEqualTo(-1);
        assertThat(step.feelsLikeC()).isNull();
        assertThat(step.precipitationProbability()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json", "[]", "{}", "{\"features\":[]}", "{\"features\":[{}]}",
            "{\"features\":[{\"properties\":{\"modelRunDate\":\"2026-11-19T06:00Z\",\"timeSeries\":[]}}]}",
            "{\"features\":[{\"properties\":{\"modelRunDate\":\"yesterday\",\"timeSeries\":[{\"time\":\"2026-11-19T07:00Z\"}]}}]}",
            "{\"features\":[{\"properties\":{\"modelRunDate\":\"2026-11-19T06:00Z\",\"timeSeries\":[{\"time\":\"07:00\"}]}}]}",
            "{\"features\":[{\"properties\":{\"modelRunDate\":\"2026-11-19T06:00Z\",\"timeSeries\":[{\"time\":\"2026-11-19T07:00Z\",\"significantWeatherCode\":31}]}}]}",
            "{\"features\":[{\"properties\":{\"modelRunDate\":\"2026-11-19T06:00Z\",\"timeSeries\":[{\"time\":\"2026-11-19T07:00Z\",\"significantWeatherCode\":7.5}]}}]}",
            "{\"features\":[{\"properties\":{\"modelRunDate\":\"2026-11-19T06:00Z\",\"timeSeries\":[{\"time\":\"2026-11-19T07:00Z\",\"screenTemperature\":\"7\"}]}}]}",
            "{\"features\":[{\"properties\":{\"modelRunDate\":\"2026-11-19T06:00Z\",\"timeSeries\":[{\"time\":\"2026-11-19T07:00Z\",\"screenTemperature\":99}]}}]}",
            "{\"features\":[{\"properties\":{\"modelRunDate\":\"2026-11-19T06:00Z\",\"timeSeries\":[{\"time\":\"2026-11-19T07:00Z\",\"probOfPrecipitation\":101}]}}]}",
            "{\"features\":[{\"properties\":{\"modelRunDate\":\"2026-11-19T06:00Z\",\"modelRunDate\":\"2026-11-19T06:00Z\",\"timeSeries\":[]}}]}"})
    void unusableResponsesAreInvalid(String json) {
        var thrown = catchThrowableOfType(WeatherFailure.class, () -> HourlyForecast.parse(bytes(json)));
        assertThat(thrown).isNotNull();
        assertThat(thrown.reason()).isEqualTo(INVALID_RESPONSE);
    }

    @Test
    void currentStepIsTheStepForTheHourContainingNowAndNeverANeighbouringHour() {
        var forecast = HourlyForecast.parse(bytes(response("2026-11-19T06:00Z",
                step("2026-11-19T07:00Z", 6, 7), step("2026-11-19T08:00Z", 7, 8), step("2026-11-19T10:00Z", 8, 1))));
        assertThat(forecast.currentStep(Instant.parse("2026-11-19T08:59:59Z")).orElseThrow().temperatureC()).isEqualTo(7);
        assertThat(forecast.currentStep(Instant.parse("2026-11-19T08:00:00Z")).orElseThrow().temperatureC()).isEqualTo(7);
        assertThat(forecast.currentStep(Instant.parse("2026-11-19T07:59:59Z")).orElseThrow().temperatureC()).isEqualTo(6);
        // Before the first step: nothing (the first step is not shown early).
        assertThat(forecast.currentStep(Instant.parse("2026-11-19T06:59:59Z"))).isEmpty();
        // A gap: no 09:00 step, so nothing at 09:30 (neither 08:00 nor 10:00 stands in for it).
        assertThat(forecast.currentStep(Instant.parse("2026-11-19T09:30:00Z"))).isEmpty();
        // After the last step's hour: nothing.
        assertThat(forecast.currentStep(Instant.parse("2026-11-19T10:59:59Z")).orElseThrow().temperatureC()).isEqualTo(8);
        assertThat(forecast.currentStep(Instant.parse("2026-11-19T11:00:00Z"))).isEmpty();
    }
}
