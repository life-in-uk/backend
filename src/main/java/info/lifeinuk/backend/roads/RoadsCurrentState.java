package info.lifeinuk.backend.roads;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresInterpretation.*;

/** One complete logical acquisition, not a page. No raw evidence or persistence entity graph. */
public record RoadsCurrentState(UUID ingestionRunId, UUID sourceEndpointId, Instant snapshotAt,
        Instant projectedAt, int pageCount, List<Record> closures) {
    public RoadsCurrentState { closures = List.copyOf(closures); }

    public record Record(String situationId, Optional<Instant> situationVersionTime,
            Optional<String> confidentiality, Optional<String> informationStatus, Closure closure) { }
}
