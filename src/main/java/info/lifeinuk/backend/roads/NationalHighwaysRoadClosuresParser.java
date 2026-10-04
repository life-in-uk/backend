package info.lifeinuk.backend.roads;

import info.lifeinuk.backend.evidence.EvidenceArtifact;
import info.lifeinuk.backend.evidence.RunStatus;
import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.source.SourceEndpoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresInterpretation.*;

/** Supplied evidence only. Caller must supply its initialized provenance chain; no repository lookup. */
@Component
public final class NationalHighwaysRoadClosuresParser {
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    public NationalHighwaysRoadClosuresInterpretation parse(EvidenceArtifact evidence) {
        Objects.requireNonNull(evidence, "Evidence is required");
        var run = evidence.getIngestionRun();
        requireInitialized(run);
        var endpoint = run.getSourceEndpoint();
        requireInitialized(endpoint);
        var source = endpoint.getSource();
        requireInitialized(source);
        if (run.getStatus() != RunStatus.SUCCESS || !endpoint.isQualifiedAndEnabled()
                || !Source.NATIONAL_HIGHWAYS_KEY.equals(source.getKey())
                || !Source.NATIONAL_HIGHWAYS_ROAD_CLOSURES_SCOPE.equals(source.getScope())
                || !SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY.equals(endpoint.getKey())
                || !SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL.equals(endpoint.getUrl())
                || evidence.getPageNumber() == null || evidence.getPageNumber() < 1
                || !Integer.valueOf(200).equals(evidence.getHttpStatus()) || evidence.getRequestedAt() == null
                || evidence.getRequestQuery() == null) {
            throw invalid("Evidence must be a successful qualified canonical National Highways acquisition page");
        }
        JsonNode root;
        try { root = json.readTree(evidence.getPayload()); }
        catch (JacksonException malformed) { throw invalid("Evidence must contain one valid JSON document"); }
        JsonNode payload = requiredObject(requiredObject(root).get("D2Payload"));
        if (!"SituationPublication".equals(text(payload.get("feedType")))) {
            throw invalid("Expected a SituationPublication");
        }
        var situations = array(payload.get("situation"), this::situation);
        return new NationalHighwaysRoadClosuresInterpretation(evidence.getId(), run.getId(), evidence.getObservedAt(),
                evidence.getRequestedAt(), evidence.getPageNumber(), evidence.getRequestQuery(),
                optionalText(payload, "version"), time(payload, "publicationTime"), situations);
    }

    private Situation situation(JsonNode node) {
        requiredObject(node);
        JsonNode header = optionalObject(node, "headerInformation");
        return new Situation(text(node.get("idG")), time(node, "situationVersionTime"),
                optionalText(header, "confidentiality"), optionalText(header, "informationStatus"),
                array(node.get("situationRecord"), this::closure));
    }
    private Closure closure(JsonNode wrapper) {
        requiredObject(wrapper);
        // Other record variants carry different semantics: never silently skip them as extra metadata.
        if (wrapper.properties().stream().anyMatch(p -> p.getKey().matches("sit[A-Z].*")
                && !p.getKey().equals("sitRoadOrCarriagewayOrLaneManagement"))) {
            throw invalid("Unsupported situation record variant");
        }
        JsonNode node = requiredObject(wrapper.get("sitRoadOrCarriagewayOrLaneManagement"));
        JsonNode source = optionalObject(node, "source");
        return new Closure(text(node.get("idG")), optionalText(node, "versionG"),
                time(node, "situationRecordCreationTime"), time(node, "situationRecordVersionTime"),
                optionalText(node, "probabilityOfOccurrence"), optionalText(node, "complianceOption"),
                optionalText(source, "sourceIdentification"), optionalCode(node, "roadOrCarriagewayOrLaneManagementType"),
                optional(node, "validity", this::validity), optional(node, "cause", this::cause),
                optionalArray(node, "generalPublicComment", c -> text(requiredObject(c).get("comment"))),
                optional(node, "locationReference", this::location).map(List::of).orElseGet(List::of));
    }
    private Validity validity(JsonNode node) {
        requiredObject(node);
        JsonNode times = optionalObject(node, "validityTimeSpecification");
        return new Validity(optionalText(node, "validityStatus"), time(times, "overallStartTime"), time(times, "overallEndTime"));
    }
    private Cause cause(JsonNode node) {
        requiredObject(node);
        return new Cause(optionalText(node, "causeType"), optionalCode(optionalObject(node, "detailedCauseType"),
                "roadOrCarriagewayOrLaneManagementType"));
    }
    private Location location(JsonNode node) {
        requiredObject(node);
        if (node.properties().stream().anyMatch(p -> p.getKey().matches("loc[A-Z].*")
                && !List.of("locLocationGroupByList", "locLinearLocation", "locSingleRoadLinearLocation").contains(p.getKey()))) {
            throw invalid("Unsupported location variant");
        }
        JsonNode group = optionalObject(node, "locLocationGroupByList");
        if (group != null) {
            if (node.has("locLinearLocation") || node.has("locSingleRoadLinearLocation")) {
                throw invalid("Ambiguous grouped and direct location");
            }
            return new LocationGroup(array(group.get("locationContainedInGroup"), this::location));
        }
        JsonNode linear = optionalObject(node, "locLinearLocation");
        JsonNode road = optionalObject(node, "locSingleRoadLinearLocation");
        if (linear == null && road == null) { throw invalid("A supported location form is required"); }
        JsonNode description = optionalObject(linear, "supplementaryPositionalDescription");
        return new LinearLocation(optional(linear, "gmlLineString", n ->
                    geometry(requiredObject(n).get("locGmlLineString"))),
                optionalText(description, "locationDescription"), optionalArray(description, "carriageway", this::carriageway),
                road == null ? List.of() : array(road.get("linearWithinLinearElement"), this::section));
    }
    private Geometry geometry(JsonNode node) {
        requiredObject(node);
        int dimension = integer(node.get("srsDimension"));
        if (dimension != 2) { throw invalid("Only the observed two-dimensional GML positions are supported"); }
        String crs = text(node.get("srsName"));
        String source = text(node.get("posList"));
        String[] tokens = source.strip().split("\\s+");
        if (tokens.length < 4 || tokens.length % dimension != 0) { throw invalid("Incomplete GML line positions"); }
        var positions = new ArrayList<List<BigDecimal>>();
        try {
            for (int i = 0; i < tokens.length; i += dimension) {
                positions.add(List.of(new BigDecimal(tokens[i]), new BigDecimal(tokens[i + 1])));
            }
        } catch (NumberFormatException invalid) { throw invalid("GML positions must be finite decimal numbers"); }
        return new Geometry(crs, dimension, source, positions);
    }
    private Carriageway carriageway(JsonNode node) {
        requiredObject(node);
        JsonNode impact = optionalObject(optionalObject(node, "carriagewayExtensionG"), "impactOnCarriageway");
        return new Carriageway(optionalCode(node, "carriageway"), optionalArray(node, "lane", this::lane),
                optionalInteger(impact, "numberOfLanesRestricted"), optionalInteger(impact, "numberOfOperationalLanes"));
    }
    private Lane lane(JsonNode node) {
        requiredObject(node);
        JsonNode impact = optionalObject(optionalObject(optionalObject(node, "laneExtensionG"), "impactOnLanes"), "impactExtensionG");
        return new Lane(optionalCode(node, "laneUsage"), optionalInteger(node, "laneNumber"),
                optionalText(impact, "lanesStatus"), optionalText(impact, "laneImpactDirection"));
    }
    private RoadSection section(JsonNode node) {
        requiredObject(node);
        JsonNode element = optionalObject(node, "linearElement");
        JsonNode code = element == null ? null : requiredObject(element.get("locLinearElementByCode"));
        return new RoadSection(optionalText(node, "directionOnLinearSection"), optionalText(node, "directionRelativeOnLinearSection"),
                optionalCode(node, "heightGradeOfLinearSection"), optionalText(code, "roadName"),
                optionalText(code, "linearElementReferenceModel"), optionalText(code, "linearElementIdentifier"),
                optionalText(optionalObject(code, "linearElementByCodeExtensionG"), "linearElementType"),
                distance(node, "fromPoint"), distance(node, "toPoint"));
    }
    private Optional<BigDecimal> distance(JsonNode node, String field) {
        return optional(node, field, point -> {
            JsonNode value = requiredObject(requiredObject(point).get("locDistanceFromLinearElementStart")).get("distanceAlong");
            if (value == null || !value.isNumber()) { throw invalid("Linear distance must be numeric"); }
            BigDecimal distance = value.decimalValue();
            if (distance.signum() < 0) { throw invalid("Linear distance must be nonnegative"); }
            return distance;
        });
    }
    private Optional<Code> optionalCode(JsonNode node, String field) {
        return optional(node, field, n -> new Code(text(requiredObject(n).get("value")), optionalText(n, "extendedValueG")));
    }
    private Optional<Instant> time(JsonNode node, String field) {
        return optional(node, field, n -> {
            String value = text(n);
            // Strict calendar validation and explicit offset; reject leap-second normalization and offset-free times.
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})")) {
                throw invalid("Provider timestamp must be an ISO date-time with offset");
            }
            try { return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(); }
            catch (DateTimeParseException invalid) { throw invalid("Invalid provider timestamp"); }
        });
    }
    private Optional<String> optionalText(JsonNode node, String field) {
        return optional(node, field, n -> { if (!n.isString()) { throw invalid("Expected source string"); } return n.stringValue(); });
    }
    private Optional<Integer> optionalInteger(JsonNode node, String field) {
        return optional(node, field, n -> { int v = integer(n); if (v < 0) { throw invalid("Expected nonnegative count"); } return v; });
    }
    private static int integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) { throw invalid("Expected 32-bit integer"); }
        return node.intValue();
    }
    private static String text(JsonNode node) {
        if (node == null || !node.isString() || node.stringValue().isBlank()) { throw invalid("Expected nonblank source string"); }
        return node.stringValue();
    }
    private static JsonNode requiredObject(JsonNode node) {
        if (node == null || !node.isObject()) { throw invalid("Expected required object"); }
        return node;
    }
    private static JsonNode optionalObject(JsonNode node, String field) {
        return optional(node, field, NationalHighwaysRoadClosuresParser::requiredObject).orElse(null);
    }
    private static <T> Optional<T> optional(JsonNode node, String field, Function<JsonNode,T> parser) {
        if (node == null || !node.has(field)) { return Optional.empty(); }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) { throw invalid("Present optional field must have its expected type"); }
        return Optional.of(parser.apply(value));
    }
    private static <T> List<T> optionalArray(JsonNode node, String field, Function<JsonNode,T> parser) {
        return optional(node, field, n -> array(n, parser)).orElseGet(List::of);
    }
    private static <T> List<T> array(JsonNode node, Function<JsonNode,T> parser) {
        if (node == null || !node.isArray()) { throw invalid("Expected required array"); }
        var result = new ArrayList<T>();
        for (JsonNode entry : node) { result.add(parser.apply(entry)); }
        return List.copyOf(result);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static void requireInitialized(Object provenance) {
        if (!Hibernate.isInitialized(provenance)) {
            throw invalid("Evidence provenance must be initialized before interpretation");
        }
    }
}
