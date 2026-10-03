package info.lifeinuk.backend.bankholidays;

import info.lifeinuk.backend.evidence.EvidenceArtifact;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure interpretation of supplied evidence: no lookup, acquisition or persistence. */
@Component
public class GovUkBankHolidaysParser {
    private static final Set<String> DIVISIONS = Arrays.stream(BankHolidayDivision.values())
            .map(BankHolidayDivision::sourceIdentifier).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> DIVISION_FIELDS = Set.of("division", "events");
    private static final Set<String> EVENT_FIELDS = Set.of("title", "date", "notes", "bunting");
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public BankHolidaysInterpretation parse(EvidenceArtifact evidence) {
        Objects.requireNonNull(evidence, "Evidence artifact is required");
        JsonNode root;
        try {
            root = json.readTree(evidence.getPayload());
        } catch (JacksonException invalid) {
            // Avoid echoing source payload or library diagnostics containing source values.
            throw new IllegalArgumentException("Evidence is not one valid JSON document");
        }
        requireFields(root, DIVISIONS, "root");
        var facts = new ArrayList<BankHolidayFact>();
        // Fixed division order; event order within each division is preserved without sorting/deduplication.
        for (BankHolidayDivision division : BankHolidayDivision.values()) {
            String path = division.sourceIdentifier();
            JsonNode group = root.get(path);
            requireFields(group, DIVISION_FIELDS, path);
            if (!path.equals(requireText(group.get("division"), path + ".division"))) {
                throw new IllegalArgumentException(path + ".division must match its supported root identifier");
            }
            JsonNode events = group.get("events");
            if (!events.isArray()) {
                throw new IllegalArgumentException(path + ".events must be an array");
            }
            for (int index = 0; index < events.size(); index++) {
                String eventPath = path + ".events[" + index + "]";
                JsonNode event = events.get(index);
                requireFields(event, EVENT_FIELDS, eventPath);
                String title = requireText(event.get("title"), eventPath + ".title");
                if (title.isBlank()) {
                    throw new IllegalArgumentException(eventPath + ".title must not be blank");
                }
                String date = requireText(event.get("date"), eventPath + ".date");
                LocalDate parsedDate;
                try {
                    if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                        throw new DateTimeParseException("Expected YYYY-MM-DD", "", 0);
                    }
                    parsedDate = LocalDate.parse(date);
                } catch (DateTimeParseException invalid) {
                    throw new IllegalArgumentException(eventPath + ".date must be a valid YYYY-MM-DD date");
                }
                String notes = requireText(event.get("notes"), eventPath + ".notes");
                JsonNode bunting = event.get("bunting");
                if (!bunting.isBoolean()) {
                    throw new IllegalArgumentException(eventPath + ".bunting must be a boolean");
                }
                facts.add(new BankHolidayFact(division, title, parsedDate, notes, bunting.booleanValue()));
            }
        }
        return new BankHolidaysInterpretation(evidence.getId(), facts);
    }

    private void requireFields(JsonNode node, Set<String> fields, String path) {
        if (node == null || !node.isObject() || node.size() != fields.size()
                || node.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
            throw new IllegalArgumentException(path + " must be an object with exactly its required fields");
        }
    }

    private String requireText(JsonNode value, String path) {
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.stringValue();
    }
}
