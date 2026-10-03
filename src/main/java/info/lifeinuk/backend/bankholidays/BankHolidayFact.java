package info.lifeinuk.backend.bankholidays;

import java.time.LocalDate;
import java.util.Objects;

/** Source facts only; strings retain their original spelling and whitespace. */
public record BankHolidayFact(BankHolidayDivision division, String title, LocalDate date,
        String notes, boolean bunting) {
    public BankHolidayFact {
        Objects.requireNonNull(division, "Division is required");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A nonblank title is required");
        }
        Objects.requireNonNull(date, "Date is required");
        Objects.requireNonNull(notes, "Notes are required; an empty string is valid");
    }
}
