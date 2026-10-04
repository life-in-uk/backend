package info.lifeinuk.backend.places;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only geographic lookup. Responses are never cacheable: a searched place is not stored anywhere. */
@RestController
public class PlacesController {
    private final PlaceSearch search;

    PlacesController(PlaceSearch search) { this.search = search; }

    @GetMapping("/api/places/search")
    public ResponseEntity<?> search(@RequestParam(value = "q", required = false) String query) {
        try {
            PlaceSearch.normalise(query);
        } catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "PLACE_QUERY_INVALID",
                    "A place query of 1 to " + PlaceSearch.MAX_QUERY_LENGTH + " characters is required.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(query));
    }

    @ExceptionHandler(PlaceSearchFailure.class)
    ResponseEntity<Error> upstream(PlaceSearchFailure failure) {
        return switch (failure.reason()) {
            case NOT_CONFIGURED -> error(HttpStatus.SERVICE_UNAVAILABLE, "PLACES_NOT_CONFIGURED", "Place search is not available.");
            case UNAVAILABLE -> error(HttpStatus.BAD_GATEWAY, "PLACES_UNAVAILABLE", "Place search is temporarily unavailable.");
            case INVALID_RESPONSE -> error(HttpStatus.BAD_GATEWAY, "PLACES_UPSTREAM_INVALID", "Place search returned an unusable response.");
        };
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> failed() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "PLACES_SEARCH_FAILED", "Place search failed.");
    }

    private static ResponseEntity<Error> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code, message));
    }

    public record Error(String code, String message) { }
}
