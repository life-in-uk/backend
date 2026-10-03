package info.lifeinuk.backend.underground;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UndergroundController {
    private final UndergroundQuery query;

    UndergroundController(UndergroundQuery query) { this.query = query; }

    @GetMapping("/api/travel/underground")
    public ResponseEntity<?> get() {
        return query.read().<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new Error("UNDERGROUND_UNAVAILABLE", "No Underground Current State is available.")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> failedRead() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new Error("UNDERGROUND_READ_FAILED", "Underground Current State could not be read."));
    }

    public record Error(String code, String message) { }
}
