package info.lifeinuk.backend.weather;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fixed set of places the homepage offers. A closed list keeps the Met Office quota bounded: at most one
 * request per city per refresh interval (8 cities × 24 hours = 192 a day, under the free plan's 360). Visitors
 * cannot make the backend request an arbitrary location. Names are shown by the frontend in each language; the
 * provider's location name (separately licensed) is not requested.
 */
public enum WeatherCity {
    LONDON("london", 51.5074, -0.1278),
    BIRMINGHAM("birmingham", 52.4862, -1.8904),
    MANCHESTER("manchester", 53.4808, -2.2426),
    LEEDS("leeds", 53.8008, -1.5491),
    GLASGOW("glasgow", 55.8642, -4.2518),
    EDINBURGH("edinburgh", 55.9533, -3.1883),
    CARDIFF("cardiff", 51.4816, -3.1791),
    BELFAST("belfast", 54.5973, -5.9301);

    private final String id;
    private final double latitude;
    private final double longitude;

    WeatherCity(String id, double latitude, double longitude) {
        this.id = id;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String id() { return id; }
    double latitude() { return latitude; }
    double longitude() { return longitude; }

    static Optional<WeatherCity> fromId(String id) {
        return Arrays.stream(values()).filter(city -> city.id.equals(id)).findFirst();
    }
}
