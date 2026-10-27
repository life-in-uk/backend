package info.lifeinuk.backend.rail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@EnableConfigurationProperties({RailProperties.class, RailDepartureProperties.class})
public class RailController {
    private final RailService rail;
    RailController(RailService rail) { this.rail = rail; }
    @GetMapping("/api/rail/stations/{crs}/arrivals")
    public ResponseEntity<?> arrivals(@PathVariable String crs, @RequestParam(required=false) String numRows, @RequestParam(required=false) String timeWindow) {
        try {
            Integer rows = number(numRows), window = number(timeWindow);
            RailService.validate(crs, rows, window);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rail.arrivals(crs, rows, window));
        } catch (IllegalArgumentException invalid) { return error(HttpStatus.BAD_REQUEST,"RAIL_REQUEST_INVALID","Invalid station code or query parameters."); }
    }
    @GetMapping("/api/rail/stations/{crs}/disruptions")
    public ResponseEntity<?> disruptions(@PathVariable String crs, @RequestParam(defaultValue="arrivals") String direction) {
        if (!direction.equals("arrivals") && !direction.equals("departures")) return error(HttpStatus.BAD_REQUEST,"RAIL_REQUEST_INVALID","Invalid direction.");
        try {
            RailService.validate(crs, null, null);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RailDisruptions.from(rail.board(crs, 9, null, direction.equals("departures")), direction));
        } catch (IllegalArgumentException invalid) { return error(HttpStatus.BAD_REQUEST,"RAIL_REQUEST_INVALID","Invalid station code."); }
    }
    private static Integer number(String value) { return value == null ? null : Integer.valueOf(value); }
    @ExceptionHandler(RailFailure.class)
    ResponseEntity<Error> upstream(RailFailure failure) {
        return switch (failure.reason) {
            case NOT_CONFIGURED -> error(HttpStatus.SERVICE_UNAVAILABLE,"RAIL_NOT_CONFIGURED","Rail information is not available.");
            case AUTHENTICATION -> error(HttpStatus.BAD_GATEWAY,"RAIL_UPSTREAM_AUTHENTICATION","Rail provider authentication failed.");
            case RATE_LIMIT -> error(HttpStatus.SERVICE_UNAVAILABLE,"RAIL_UPSTREAM_RATE_LIMIT","Rail information is temporarily unavailable.");
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT,"RAIL_UPSTREAM_TIMEOUT","Rail information timed out.");
            case INVALID_RESPONSE -> error(HttpStatus.BAD_GATEWAY,"RAIL_UPSTREAM_INVALID","Rail provider returned an unusable response.");
            case UPSTREAM_REQUEST -> error(HttpStatus.BAD_GATEWAY,"RAIL_UPSTREAM_REQUEST","Rail provider rejected the request.");
            case UPSTREAM_ERROR, NETWORK -> error(HttpStatus.BAD_GATEWAY,"RAIL_UPSTREAM_UNAVAILABLE","Rail information is temporarily unavailable.");
        };
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> unexpected() { return error(HttpStatus.INTERNAL_SERVER_ERROR,"RAIL_FAILED","Rail information failed."); }
    private static ResponseEntity<Error> error(HttpStatus status,String code,String message) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code,message)); }
    public record Error(String code,String message) { }
}
