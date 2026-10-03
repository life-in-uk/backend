package info.lifeinuk.backend.bankholidays;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BankHolidaysController {
    private final BankHolidaysQuery query;

    BankHolidaysController(BankHolidaysQuery query) {
        this.query = query;
    }

    @GetMapping("/api/bank-holidays")
    public ResponseEntity<?> get() {
        return query.read().<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new Error("BANK_HOLIDAYS_UNAVAILABLE", "No usable Bank Holidays evidence is available.")));
    }

    @ExceptionHandler(BankHolidaysQuery.InvalidEvidence.class)
    ResponseEntity<Error> invalidEvidence() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new Error("BANK_HOLIDAYS_INVALID_EVIDENCE", "Stored Bank Holidays evidence could not be interpreted."));
    }

    public record Error(String code, String message) { }
}
