package info.lifeinuk.backend.bankholidays;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Explicit public contract; no persistence entity or raw evidence is serialized. */
public record BankHolidaysResponse(Evidence evidence, List<Division> divisions) {
    public BankHolidaysResponse {
        divisions = List.copyOf(divisions);
    }

    static BankHolidaysResponse from(BankHolidaysInterpretation interpretation, Instant observedAt) {
        return new BankHolidaysResponse(new Evidence(interpretation.evidenceArtifactId(), observedAt),
                Arrays.stream(BankHolidayDivision.values()).map(division -> new Division(
                        division.sourceIdentifier(), interpretation.facts().stream()
                                .filter(fact -> fact.division() == division)
                                .map(fact -> new Event(fact.title(), fact.date(), fact.notes(), fact.bunting()))
                                .toList())).toList());
    }

    public record Evidence(UUID artifactId, Instant observedAt) { }
    public record Division(String division, List<Event> events) {
        public Division { events = List.copyOf(events); }
    }
    public record Event(String title, LocalDate date, String notes, boolean bunting) { }
}
