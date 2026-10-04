package info.lifeinuk.backend.roads;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One evidence page, not a complete acquisition snapshot. Source facts only. */
public record NationalHighwaysRoadClosuresInterpretation(UUID evidenceArtifactId, UUID ingestionRunId,
        Instant observedAt, Instant requestedAt, int pageNumber, String requestQuery,
        Optional<String> modelVersion, Optional<Instant> publicationTime, List<Situation> situations) {
    public NationalHighwaysRoadClosuresInterpretation { situations = List.copyOf(situations); }

    public record Situation(String id, Optional<Instant> versionTime, Optional<String> confidentiality,
            Optional<String> informationStatus, List<Closure> records) {
        public Situation { records = List.copyOf(records); }
    }
    public record Closure(String id, Optional<String> version, Optional<Instant> creationTime,
            Optional<Instant> versionTime, Optional<String> probability, Optional<String> compliance,
            Optional<String> sourceIdentification, Optional<Code> managementType, Optional<Validity> validity,
            Optional<Cause> cause, List<String> publicComments, List<Location> locations) {
        public Closure { publicComments = List.copyOf(publicComments); locations = List.copyOf(locations); }
    }
    /** Extended values are retained alongside the provider enum value, never substituted for it. */
    public record Code(String value, Optional<String> extendedValue) { }
    public record Validity(Optional<String> status, Optional<Instant> start, Optional<Instant> end) { }
    public record Cause(Optional<String> type, Optional<Code> managementType) { }
    public sealed interface Location permits LinearLocation, LocationGroup { }
    public record LocationGroup(List<Location> locations) implements Location {
        public LocationGroup { locations = List.copyOf(locations); }
    }
    /** Complementary GML geometry and network linear references describe the same provider location. */
    public record LinearLocation(Optional<Geometry> geometry, Optional<String> description,
            List<Carriageway> carriageways, List<RoadSection> roadSections) implements Location {
        public LinearLocation { carriageways = List.copyOf(carriageways); roadSections = List.copyOf(roadSections); }
    }
    /** Ordered source ordinates, with no inferred axis order or CRS correction/transformation. */
    public record Geometry(String srsName, int dimension, String sourcePositionList, List<List<BigDecimal>> positions) {
        public Geometry { positions = positions.stream().map(List::copyOf).toList(); }
    }
    public record Carriageway(Optional<Code> type, List<Lane> lanes, Optional<Integer> restrictedLanes,
            Optional<Integer> operationalLanes) {
        public Carriageway { lanes = List.copyOf(lanes); }
    }
    public record Lane(Optional<Code> usage, Optional<Integer> number, Optional<String> status,
            Optional<String> impactDirection) { }
    public record RoadSection(Optional<String> direction, Optional<String> relativeDirection,
            Optional<Code> heightGrade, Optional<String> roadName, Optional<String> referenceModel,
            Optional<String> elementId, Optional<String> elementType,
            Optional<BigDecimal> fromDistance, Optional<BigDecimal> toDistance) { }
}
