package info.lifeinuk.backend.weather;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Current-hour Met Office forecast for one of the fixed homepage cities. */
@RestController
public class WeatherController {
    private final WeatherService service;

    WeatherController(WeatherService service) { this.service = service; }

    /**
     * With {@code city}: that city (anything but one of the fixed ids is 400). Without it: the nearest fixed city to
     * CloudFront's approximate viewer location, or London ({@link WeatherCityDetection}). Same cache either way.
     */
    @GetMapping("/api/weather/current")
    public ResponseEntity<?> current(@RequestParam(value = "city", required = false) String city, HttpServletRequest request) {
        WeatherCityDetection.Choice choice;
        if (city == null) {
            choice = WeatherCityDetection.detect(request::getHeader);
        } else {
            var requested = WeatherCity.fromId(city);
            if (requested.isEmpty()) {
                return error(HttpStatus.BAD_REQUEST, "WEATHER_CITY_INVALID", "Choose one of the listed cities.");
            }
            choice = new WeatherCityDetection.Choice(requested.get(), WeatherCityDetection.Source.REQUESTED);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.current(choice.city()).withCitySource(choice.source().id));
    }

    @ExceptionHandler(WeatherFailure.class)
    ResponseEntity<Error> upstream(WeatherFailure failure) {
        return switch (failure.reason()) {
            case NOT_CONFIGURED -> error(HttpStatus.SERVICE_UNAVAILABLE, "WEATHER_NOT_CONFIGURED", "Weather is not available.");
            case UNAVAILABLE -> error(HttpStatus.BAD_GATEWAY, "WEATHER_UNAVAILABLE", "Weather is temporarily unavailable.");
            case INVALID_RESPONSE -> error(HttpStatus.BAD_GATEWAY, "WEATHER_UPSTREAM_INVALID", "Weather returned an unusable response.");
        };
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> failed() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "WEATHER_FAILED", "Weather failed.");
    }

    private static ResponseEntity<Error> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code, message));
    }

    public record Error(String code, String message) { }
}
