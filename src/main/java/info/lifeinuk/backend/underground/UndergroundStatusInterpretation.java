package info.lifeinuk.backend.underground;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** In-memory source facts from one immutable observation; no current-state decisions. */
public record UndergroundStatusInterpretation(UUID evidenceArtifactId, Instant observedAt,
        List<UndergroundLineStatus> lines) {
    public UndergroundStatusInterpretation {
        Objects.requireNonNull(evidenceArtifactId, "Evidence identity is required");
        Objects.requireNonNull(observedAt, "Observation time is required");
        lines = List.copyOf(lines);
    }
}
