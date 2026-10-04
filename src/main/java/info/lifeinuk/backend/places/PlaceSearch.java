package info.lifeinuk.backend.places;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static info.lifeinuk.backend.places.PlaceSearchFailure.Reason.INVALID_RESPONSE;

/**
 * Geographic place lookup for the Roads card. Stateless: the query and results are neither logged, persisted,
 * cached nor associated with anyone; the only destination is the approved OS Names API.
 */
@Service
public class PlaceSearch {
    static final int MAX_QUERY_LENGTH = 100;
    private static final Map<String, String> TYPES = Map.of("Postcode", "postcode", "City", "city", "Town", "town",
            "Village", "village", "Hamlet", "hamlet", "Suburban Area", "suburb", "Other Settlement", "settlement");
    private final OsNamesClient client;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @Autowired
    PlaceSearch(OsNamesClient client) { this(client, Clock.systemUTC()); }

    PlaceSearch(OsNamesClient client, Clock clock) { this.client = client; this.clock = clock; }

    /** Validates and normalises a free-text query; throws IllegalArgumentException for unusable input. */
    static String normalise(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("A place query is required");
        }
        String trimmed = query.strip().replaceAll("\\s+", " ");
        if (trimmed.length() > MAX_QUERY_LENGTH || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Place query is too long or contains control characters");
        }
        return trimmed;
    }

    public PlaceSearchResponse search(String rawQuery) {
        String query = normalise(rawQuery);
        JsonNode root;
        try {
            root = json.readTree(client.find(query));
        } catch (JacksonException malformed) {
            throw new PlaceSearchFailure(INVALID_RESPONSE);
        }
        var places = new ArrayList<PlaceSearchResponse.Place>();
        for (JsonNode entry : results(root)) {
            JsonNode place = entry.isObject() ? entry.get("GAZETTEER_ENTRY") : null;
            if (place == null || !place.isObject()) { throw new PlaceSearchFailure(INVALID_RESPONSE); }
            // fq already restricts types; anything else returned is outside this feature's scope.
            String type = TYPES.get(required(place, "LOCAL_TYPE").replace('_', ' '));
            if (type == null) { continue; }
            places.add(normalisePlace(place, type));
            if (places.size() == OsNamesClient.MAX_RESULTS) { break; }
        }
        return new PlaceSearchResponse(query, places, attribution());
    }

    String attribution() {
        return "Contains OS data © Crown copyright and database right " + clock.instant().atZone(ZoneOffset.UTC).getYear();
    }

    private static Iterable<JsonNode> results(JsonNode root) {
        JsonNode header = root != null && root.isObject() ? root.get("header") : null;
        JsonNode total = header != null && header.isObject() ? header.get("totalresults") : null;
        if (total == null || !total.isIntegralNumber() || total.longValue() < 0) {
            throw new PlaceSearchFailure(INVALID_RESPONSE);
        }
        JsonNode results = root.get("results");
        if (results == null) {
            // OS omits "results" when nothing matches; only then is absence a valid empty answer.
            if (total.longValue() != 0) { throw new PlaceSearchFailure(INVALID_RESPONSE); }
            return java.util.List.of();
        }
        if (!results.isArray()) { throw new PlaceSearchFailure(INVALID_RESPONSE); }
        return results;
    }

    private static PlaceSearchResponse.Place normalisePlace(JsonNode place, String type) {
        String id = required(place, "ID"), name = required(place, "NAME1");
        double easting = coordinate(place, "GEOMETRY_X"), northing = coordinate(place, "GEOMETRY_Y");
        if (!BritishNationalGrid.covers(easting, northing)) { throw new PlaceSearchFailure(INVALID_RESPONSE); }
        var position = BritishNationalGrid.toWgs84(easting, northing);
        Optional<String> populated = optional(place, "POPULATED_PLACE"), district = optional(place, "DISTRICT_BOROUGH"),
                county = optional(place, "COUNTY_UNITARY"), region = optional(place, "REGION"), country = optional(place, "COUNTRY");
        String area = populated.or(() -> district).or(() -> county).filter(value -> !value.equalsIgnoreCase(name)).orElse(null);
        var parts = new LinkedHashSet<String>();
        for (Optional<String> part : java.util.List.of(Optional.of(name), populated, district.or(() -> county), region)) {
            part.filter(value -> parts.stream().noneMatch(existing -> existing.equalsIgnoreCase(value))).ifPresent(parts::add);
        }
        return new PlaceSearchResponse.Place(id, parts.stream().collect(Collectors.joining(", ")), name, type, area,
                region.orElse(null), country.orElse(null), position.latitude(), position.longitude());
    }

    private static String required(JsonNode node, String field) {
        return optional(node, field).orElseThrow(() -> new PlaceSearchFailure(INVALID_RESPONSE));
    }

    private static Optional<String> optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) { return Optional.empty(); }
        if (!value.isString()) { throw new PlaceSearchFailure(INVALID_RESPONSE); }
        String text = value.stringValue().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    private static double coordinate(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new PlaceSearchFailure(INVALID_RESPONSE);
        }
        return value.doubleValue();
    }
}
