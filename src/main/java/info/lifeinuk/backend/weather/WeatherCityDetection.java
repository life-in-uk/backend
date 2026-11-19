package info.lifeinuk.backend.weather;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;

/**
 * Picks the homepage weather city for a visitor who has not chosen one: the nearest of the fixed cities to the
 * approximate location CloudFront derives from the viewer's IP address (headers {@code CloudFront-Viewer-Country},
 * {@code -Latitude} and {@code -Longitude}, forwarded by the distribution's API origin request policy).
 *
 * <p>Only a UK ({@code GB}) location inside Great Britain and Northern Ireland's bounds counts; anything else, or
 * missing or malformed headers, gives the default, London. The coordinates are used here in memory to choose one of
 * the eight cities and are never logged, stored or returned, and no IP address is read. The result is one of the same
 * eight cities (and cache keys) as a manual choice, so detection adds no Met Office calls. A forged header can only
 * pick another of the eight cities.
 */
final class WeatherCityDetection {
    static final String COUNTRY = "CloudFront-Viewer-Country";
    static final String LATITUDE = "CloudFront-Viewer-Latitude";
    static final String LONGITUDE = "CloudFront-Viewer-Longitude";
    static final WeatherCity DEFAULT = WeatherCity.LONDON;
    private static final double MIN_LAT = 49.8, MAX_LAT = 60.9, MIN_LON = -8.7, MAX_LON = 1.8;

    /** How the city was chosen; returned as {@code citySource}. */
    enum Source {
        REQUESTED("requested"), DETECTED("detected"), DEFAULT("default");
        final String id;
        Source(String id) { this.id = id; }
    }

    record Choice(WeatherCity city, Source source) { }

    private WeatherCityDetection() { }

    /** {@code header} returns a request header's value, or null. */
    static Choice detect(Function<String, String> header) {
        if (!"GB".equals(header.apply(COUNTRY))) { return new Choice(DEFAULT, Source.DEFAULT); }
        Optional<Double> latitude = coordinate(header.apply(LATITUDE), MIN_LAT, MAX_LAT);
        Optional<Double> longitude = coordinate(header.apply(LONGITUDE), MIN_LON, MAX_LON);
        if (latitude.isEmpty() || longitude.isEmpty()) { return new Choice(DEFAULT, Source.DEFAULT); }
        WeatherCity nearest = java.util.Arrays.stream(WeatherCity.values())
                .min(Comparator.comparingDouble(city -> distanceKm(latitude.get(), longitude.get(), city)))
                .orElse(DEFAULT);
        return new Choice(nearest, Source.DETECTED);
    }

    private static Optional<Double> coordinate(String value, double min, double max) {
        if (value == null || value.length() > 20 || !value.matches("-?[0-9]{1,3}(\\.[0-9]{1,8})?")) { return Optional.empty(); }
        double parsed = Double.parseDouble(value);
        return parsed >= min && parsed <= max ? Optional.of(parsed) : Optional.empty();
    }

    /** Great-circle distance in kilometres. */
    static double distanceKm(double latitude, double longitude, WeatherCity city) {
        double dLat = Math.toRadians(city.latitude() - latitude);
        double dLon = Math.toRadians(city.longitude() - longitude);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(city.latitude())) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371.0 * 2 * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
