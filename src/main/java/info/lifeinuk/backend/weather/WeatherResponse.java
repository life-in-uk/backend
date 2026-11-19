package info.lifeinuk.backend.weather;

/**
 * Public contract: the Met Office hourly spot forecast step for the current hour at one of the fixed cities.
 * Only the few fields the homepage shows are passed on (no raw provider payload). {@code forecastTime} is the
 * step's time, {@code modelRunAt} the forecast run it comes from and {@code fetchedAt} when the backend last
 * read it from the Met Office. Temperatures are °C; {@code weatherCode} is the Met Office significant weather
 * code (-1 to 30); {@code precipitationProbability} is a percentage. Nullable numbers are absent upstream.
 */
public record WeatherResponse(String city, String forecastTime, String modelRunAt, String fetchedAt,
        Double temperatureC, Double feelsLikeC, Integer weatherCode, Integer precipitationProbability,
        String attribution) {
    public static final String ATTRIBUTION = "Data supplied by the Met Office";
}
