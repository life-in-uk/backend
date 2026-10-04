package info.lifeinuk.backend.roads;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
public class RoadsController {
    private final RoadsQuery query;
    RoadsController(RoadsQuery query) { this.query = query; }

    @GetMapping("/api/travel/roads")
    public ResponseEntity<?> get(@RequestParam("lat") double latitude, @RequestParam("lon") double longitude) {
        try { RoadsSpatial.validateLocation(latitude, longitude); }
        catch (IllegalArgumentException invalid) { return invalidLocation(); }
        return query.read(latitude, longitude).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(new Error("ROADS_UNAVAILABLE", "No Roads Current State is available.")));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> invalidLocation() {
        return ResponseEntity.badRequest().body(new Error("ROADS_LOCATION_INVALID", "Valid latitude and longitude are required."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> failedRead() {
        return ResponseEntity.internalServerError().body(new Error("ROADS_READ_FAILED", "Roads Current State could not be read."));
    }
    public record Error(String code, String message) { }
}
