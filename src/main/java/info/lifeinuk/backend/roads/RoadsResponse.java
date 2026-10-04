package info.lifeinuk.backend.roads;

import java.time.Instant;
import java.util.List;
import static info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresInterpretation.*;

/** Frontend contract: provider identities and factual values, never internal acquisition IDs. */
public record RoadsResponse(Instant snapshotAt, double relevanceRadiusMeters, List<Disruption> disruptions) {
    public RoadsResponse { disruptions = List.copyOf(disruptions); }
    public record Disruption(String situationId, String recordId, String recordVersion, List<String> descriptions,
            CodeValue type, CauseValue cause, String status, Instant startTime, Instant endTime,
            double distanceMeters, List<LocationValue> locations) {
        public Disruption { descriptions = List.copyOf(descriptions); locations = List.copyOf(locations); }
    }
    public record CodeValue(String value, String extendedValue) { }
    public record CauseValue(String type, CodeValue managementType) { }
    public record Road(String name, String direction, String relativeDirection) { }
    public record Coordinate(double latitude, double longitude) { }
    public record LocationValue(String description, List<Road> roads, List<Coordinate> coordinates) {
        public LocationValue { roads = List.copyOf(roads); coordinates = List.copyOf(coordinates); }
    }

    static Disruption from(RoadsCurrentState.Record record, RoadsSpatial.Match match) {
        var c = record.closure();
        return new Disruption(record.situationId(), c.id(), c.version().orElse(null), c.publicComments(),
                c.managementType().map(RoadsResponse::code).orElse(null),
                c.cause().map(cause -> new CauseValue(cause.type().orElse(null), cause.managementType().map(RoadsResponse::code).orElse(null))).orElse(null),
                c.validity().flatMap(Validity::status).orElse(null), c.validity().flatMap(Validity::start).orElse(null),
                c.validity().flatMap(Validity::end).orElse(null), match.distanceMeters(),
                match.components().stream().map(component -> new LocationValue(component.source().description().orElse(null),
                        component.source().roadSections().stream().map(road -> new Road(road.roadName().orElse(null),
                                road.direction().orElse(null), road.relativeDirection().orElse(null))).toList(),
                        component.positions().stream().map(p -> new Coordinate(p.latitude(), p.longitude())).toList())).toList());
    }
    private static CodeValue code(Code source) { return new CodeValue(source.value(), source.extendedValue().orElse(null)); }
}
