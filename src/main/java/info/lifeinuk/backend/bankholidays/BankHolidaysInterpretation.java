package info.lifeinuk.backend.bankholidays;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** In-memory interpretation of one specific immutable observation. */
public record BankHolidaysInterpretation(UUID evidenceArtifactId, List<BankHolidayFact> facts) {
    public BankHolidaysInterpretation {
        Objects.requireNonNull(evidenceArtifactId, "Evidence artifact identity is required");
        facts = List.copyOf(facts);
    }
}
