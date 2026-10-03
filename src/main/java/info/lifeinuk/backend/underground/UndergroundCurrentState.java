package info.lifeinuk.backend.underground;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Latest successfully projected observation, not a guarantee of service status right now. */
public record UndergroundCurrentState(UUID evidenceArtifactId, Instant observedAt, List<UndergroundLineStatus> lines) {
    public UndergroundCurrentState {
        Objects.requireNonNull(evidenceArtifactId, "Evidence identity is required");
        Objects.requireNonNull(observedAt, "Observation time is required");
        lines = List.copyOf(lines);
    }
}
