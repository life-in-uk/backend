package info.lifeinuk.backend.underground;

import java.util.Objects;
import java.util.Optional;

/** TfL source values, without severity remapping or reason rewriting. */
public record UndergroundOperationalStatus(int severity, String description, Optional<String> reason) {
    public UndergroundOperationalStatus {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A nonblank status description is required");
        }
        Objects.requireNonNull(reason, "Reason presence is required");
    }
}
