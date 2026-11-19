package info.lifeinuk.backend.weather;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static info.lifeinuk.backend.weather.WeatherFailure.Reason.INVALID_RESPONSE;

/** The parts of a Met Office hourly spot forecast that the homepage uses, validated. */
record HourlyForecast(Instant modelRunAt, List<Step> steps) {
    static final int MAX_STEPS = 200;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    record Step(Instant time, Double temperatureC, Double feelsLikeC, Integer weatherCode,
            Integer precipitationProbability) { }

    HourlyForecast {
        steps = List.copyOf(steps);
    }

    /**
     * The step for the hour containing {@code now}: the latest step at or before it, else the first step after it,
     * as long as it is within {@code tolerance} of now. Empty when the forecast no longer (or not yet) covers now.
     */
    Optional<Step> stepAt(Instant now, Duration tolerance) {
        Optional<Step> current = steps.stream().filter(step -> !step.time().isAfter(now))
                .max(Comparator.comparing(Step::time));
        Optional<Step> chosen = current.isPresent() ? current
                : steps.stream().filter(step -> step.time().isAfter(now)).min(Comparator.comparing(Step::time));
        return chosen.filter(step -> Duration.between(step.time(), now).abs().compareTo(tolerance) <= 0);
    }

    static HourlyForecast parse(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException malformed) {
            throw new WeatherFailure(INVALID_RESPONSE);
        }
        JsonNode features = root.isObject() ? root.get("features") : null;
        if (features == null || !features.isArray() || features.isEmpty() || !features.get(0).isObject()) {
            throw new WeatherFailure(INVALID_RESPONSE);
        }
        JsonNode properties = features.get(0).get("properties");
        if (properties == null || !properties.isObject()) { throw new WeatherFailure(INVALID_RESPONSE); }
        Instant modelRun = instant(properties.get("modelRunDate"));
        JsonNode series = properties.get("timeSeries");
        if (series == null || !series.isArray() || series.isEmpty() || series.size() > MAX_STEPS) {
            throw new WeatherFailure(INVALID_RESPONSE);
        }
        var steps = new ArrayList<Step>();
        for (JsonNode entry : series) {
            if (!entry.isObject()) { throw new WeatherFailure(INVALID_RESPONSE); }
            // The published schema nests parameters under "data"; responses seen in practice are flat. Accept both.
            JsonNode values = entry.get("data") != null && entry.get("data").isObject() ? entry.get("data") : entry;
            steps.add(new Step(instant(entry.get("time")),
                    number(values, "screenTemperature", -80, 60),
                    number(values, "feelsLikeTemperature", -100, 70),
                    integer(values, "significantWeatherCode", -1, 30),
                    percentage(values, "probOfPrecipitation")));
        }
        return new HourlyForecast(modelRun, steps);
    }

    private static Instant instant(JsonNode value) {
        if (value == null || !value.isString() || value.asString().length() > 40) {
            throw new WeatherFailure(INVALID_RESPONSE);
        }
        try {
            return OffsetDateTime.parse(value.asString()).toInstant();
        } catch (DateTimeParseException invalid) {
            throw new WeatherFailure(INVALID_RESPONSE);
        }
    }

    private static Double number(JsonNode values, String name, double min, double max) {
        JsonNode value = values.get(name);
        if (value == null || value.isNull()) { return null; }
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < min || value.asDouble() > max) {
            throw new WeatherFailure(INVALID_RESPONSE);
        }
        return value.asDouble();
    }

    private static Integer integer(JsonNode values, String name, int min, int max) {
        Double value = number(values, name, min, max);
        if (value == null) { return null; }
        if (value != Math.rint(value)) { throw new WeatherFailure(INVALID_RESPONSE); }
        return value.intValue();
    }

    private static Integer percentage(JsonNode values, String name) {
        Double value = number(values, name, 0, 100);
        return value == null ? null : (int) Math.round(value);
    }
}
