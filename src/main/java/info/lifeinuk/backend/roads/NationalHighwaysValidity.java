package info.lifeinuk.backend.roads;

import java.time.Instant;
import java.util.Optional;
import static info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresInterpretation.Validity;

/**
 * Read-time currency of a stored National Highways record. Withholds only records that authoritative
 * semantics prove non-current; it never rewrites, removes or reinterprets the stored provider facts.
 *
 * <p>National Highways Road and Lane Closures v2 contract: {@code validityStatus} is the "Specification of
 * validity, either explicitly overriding the validity time specification or confirming it".
 * DATEX II v3.4 (Common, Validity): "active" is "temporarily valid regardless of the validity time
 * specification"; "suspended" is "temporarily invalid regardless of the validity time specification".
 * DATEX II ValidityStatusEnum: "planned" is "currently planned regardless of the definition of the validity
 * time specification"; "definedByValidityTimeSpec" is "in accordance with the definition of the validity
 * time specification".
 *
 * <p>Therefore an {@code active} record stays current after its overallEndTime, a {@code suspended} or
 * {@code planned} record is not current whatever its times say, and only {@code definedByValidityTimeSpec}
 * is evaluated against its bounding period. Absent or unrecognised status is not proof of non-currency.
 */
final class NationalHighwaysValidity {
    private NationalHighwaysValidity() { }

    static boolean current(Optional<Validity> validity, Instant at) {
        if (validity.isEmpty()) {
            return true;
        }
        Validity value = validity.get();
        return switch (value.status().orElse("")) {
            case "suspended", "planned" -> false;
            // Bounding period: both overall bounds are inclusive; a missing bound is unbounded.
            case "definedByValidityTimeSpec" -> value.start().map(start -> !at.isBefore(start)).orElse(true)
                    && value.end().map(end -> !at.isAfter(end)).orElse(true);
            default -> true;
        };
    }
}
