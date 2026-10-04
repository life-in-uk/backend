package info.lifeinuk.backend.roads;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Current State only. No raw evidence, interpretation, projection or network in the GET path.
 * Records proven non-current by provider validity semantics are withheld at read time only.
 */
@Service
public class RoadsQuery {
    private final RoadsCurrentStates states;
    private final Clock clock;

    @Autowired
    RoadsQuery(RoadsCurrentStates states) { this(states, Clock.systemUTC()); }

    // Deterministic seam for validity-period evaluation.
    RoadsQuery(RoadsCurrentStates states, Clock clock) { this.states = states; this.clock = clock; }

    public Optional<RoadsResponse> read(double latitude, double longitude) {
        RoadsSpatial.validateLocation(latitude, longitude);
        Instant at = clock.instant();
        return states.current().map(state -> {
            var results = new ArrayList<RoadsResponse.Disruption>();
            for (var record : state.closures()) {
                if (!NationalHighwaysValidity.current(record.closure().validity(), at)) { continue; }
                RoadsSpatial.match(record.closure().locations(), latitude, longitude)
                        .filter(match -> match.distanceMeters() <= RoadsSpatial.RADIUS_METERS)
                        .ifPresent(match -> results.add(RoadsResponse.from(record, match)));
            }
            // Stable sort: equal distance/identity keeps source page/situation/record order, including duplicates.
            results.sort(Comparator.comparingDouble(RoadsResponse.Disruption::distanceMeters)
                    .thenComparing(RoadsResponse.Disruption::situationId).thenComparing(RoadsResponse.Disruption::recordId));
            return new RoadsResponse(state.snapshotAt(), RoadsSpatial.RADIUS_METERS, results);
        });
    }
}
