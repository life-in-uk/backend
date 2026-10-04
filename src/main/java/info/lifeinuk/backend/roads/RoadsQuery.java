package info.lifeinuk.backend.roads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Current State only. No raw evidence, interpretation, projection or network in the GET path. */
@Service
public class RoadsQuery {
    private final RoadsCurrentStates states;
    RoadsQuery(RoadsCurrentStates states) { this.states = states; }

    public Optional<RoadsResponse> read(double latitude, double longitude) {
        RoadsSpatial.validateLocation(latitude, longitude);
        return states.current().map(state -> {
            var results = new ArrayList<RoadsResponse.Disruption>();
            for (var record : state.closures()) {
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
